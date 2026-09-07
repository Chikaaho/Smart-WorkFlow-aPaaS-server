package com.sw.ck.bootstrap.verify;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.entity.SqlExecutionAudit;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.bpm.engine.service.SqlExecutionAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BPM 外部数据源执行引擎 H2 仿真验证。
 */
@Profile("dev")
@Component
public class BpmVerificationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BpmVerificationRunner.class);

    private final ExternalDatasourceService dsService;
    private final SqlExecutor sqlExecutor;
    private final SqlExecutionAuditService auditService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public BpmVerificationRunner(ExternalDatasourceService dsService,
                                  SqlExecutor sqlExecutor,
                                  SqlExecutionAuditService auditService,
                                  org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.dsService = dsService;
        this.sqlExecutor = sqlExecutor;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        log.info("========== BPM 外部数据源执行引擎仿真验证开始 ==========");

        // 持久库（PG local profile / dev 外接库）重启时，上次运行的残留行会让
        // uk_sw_bpm_ext_ds_name(name, deleted) 唯一键在插入或逻辑删除时冲突。
        // 该行是本 Runner 自造的验证夹具，物理清除是幂等重跑的前提。
        jdbcTemplate.update("DELETE FROM sw_bpm_ext_datasource WHERE name = ?", "verify-h2-self");

        // 创建指向 H2 自身的测试数据源
        ExternalDatasource ds = new ExternalDatasource();
        ds.setName("verify-h2-self");
        ds.setType("h2");
        ds.setJdbcUrl("jdbc:h2:mem:smart_workflow;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setDriverClass("org.h2.Driver");
        ds.setUsername("sa");
        ds.setReadOnly(0);
        ds.setEnabled(1);
        dsService.saveWithEncryption(ds, "");
        Long dsId = ds.getId();
        log.info("[验证] 测试数据源已创建: id={}, name={}", dsId, ds.getName());

        Long operatorId = 0L;
        String operatorName = "verify-runner";

        // ---- 测试 1: 合法 SELECT ----
        try {
            var result = sqlExecutor.execute(dsId, "SELECT 1 AS num", operatorId, operatorName);
            log.info("[验证] 测试1 PASS: SELECT 成功, rows={}, cols={}, time={}ms",
                    result.getRowCount(), result.getColumns(), result.getExecutionTimeMs());
        } catch (Exception e) {
            log.error("[验证] 测试1 FAIL: SELECT 应成功但被拒: {}", e.getMessage());
        }

        // ---- 测试 2: DROP TABLE 被拒 ----
        try {
            sqlExecutor.execute(dsId, "DROP TABLE sw_bpm_ext_datasource", operatorId, operatorName);
            log.error("[验证] 测试2 FAIL: DROP TABLE 应被拒绝但放行了！");
        } catch (Exception e) {
            log.info("[验证] 测试2 PASS: DROP TABLE 被拒绝: {}", e.getMessage());
        }

        // ---- 测试 3: UPDATE 被拒 ----
        try {
            sqlExecutor.execute(dsId, "UPDATE sw_bpm_ext_datasource SET name='hacked'", operatorId, operatorName);
            log.error("[验证] 测试3 FAIL: UPDATE 应被拒绝但放行了！");
        } catch (Exception e) {
            log.info("[验证] 测试3 PASS: UPDATE 被拒绝: {}", e.getMessage());
        }

        // ---- 测试 4: 堆叠语句 a;b 被拒 ----
        try {
            sqlExecutor.execute(dsId, "SELECT 1; DROP TABLE sw_bpm_ext_datasource", operatorId, operatorName);
            log.error("[验证] 测试4 FAIL: 堆叠语句应被拒绝但放行了！");
        } catch (Exception e) {
            log.info("[验证] 测试4 PASS: 堆叠语句被拒绝: {}", e.getMessage());
        }

        // ---- 测试 5: INTO OUTFILE 黑名单 ----
        try {
            sqlExecutor.execute(dsId, "SELECT * INTO OUTFILE '/tmp/hacked' FROM sys_tenant", operatorId, operatorName);
            log.error("[验证] 测试5 FAIL: INTO OUTFILE 应被拒绝但放行了！");
        } catch (Exception e) {
            log.info("[验证] 测试5 PASS: INTO OUTFILE 被拒绝: {}", e.getMessage());
        }

        // ---- 审计日志 ----
        List<SqlExecutionAudit> audits = auditService.lambdaQuery()
                .eq(SqlExecutionAudit::getDatasourceId, dsId)
                .list();
        log.info("[验证] 审计日志: 共 {} 条", audits.size());
        for (SqlExecutionAudit a : audits) {
            String sqlPreview = a.getSqlText() != null && a.getSqlText().length() > 80
                    ? a.getSqlText().substring(0, 80) + "..." : a.getSqlText();
            log.info("  ┌─ [审计样例] ds={}, success={}, rows={}, time={}ms, operator={}, sql={}",
                    a.getDatasourceName(), a.getSuccess(), a.getRowCount(),
                    a.getExecutionTimeMs(), a.getOperatorName(), sqlPreview);
        }

        // ---- 清理 ----
        dsService.removeById(dsId);
        log.info("[验证] 测试数据源已删除");

        log.info("========== BPM 外部数据源执行引擎仿真验证完成 ==========");
    }
}
