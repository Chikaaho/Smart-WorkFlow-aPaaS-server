package com.sw.ck.bootstrap.verify;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 启动后自动验证多数据源 + 租户 DS 感知：
 * <ol>
 *   <li>在 iot 扩展库建 verify_iot 表（无 tenant_id 列）</li>
 *   <li>主库查询 → SQL 应带 tenant_id 过滤</li>
 *   <li>手动 push("iot") 后查询 → SQL 不应出现 tenant_id</li>
 * </ol>
 * <p>
 * 关键设计：Service 层用 @DS（Spring AOP，先切源再进 MyBatis），
 * Mapper 上不放 @DS（MyBatis Plugin 切源在 MP 拦截器之后，太晚）。
 * 本类手动 push/poll 模拟 Service 层 @DS 行为。
 * </p>
 */
@Profile("dev")
@Component
public class VerificationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VerificationRunner.class);

    private final VerifyMapper verifyMapper;
    private final DataSource dataSource;

    public VerificationRunner(VerifyMapper verifyMapper, DataSource dataSource) {
        this.verifyMapper = verifyMapper;
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        log.info("========== 多数据源 + 租户 DS 感知验证开始 ==========");

        // 仅 dev 验证骨架：以系统操作人身份（租户 0）运行——I5 fail-closed 的租户
        // 拦截器要求业务查询携带已认证身份，本 Runner 无真实登录态
        com.sw.ck.security.holder.LoginUser systemOperator = new com.sw.ck.security.holder.LoginUser();
        systemOperator.setUserId(0L);
        systemOperator.setTenantId(0L);
        systemOperator.setUsername("verify-runner");
        com.sw.ck.security.holder.LoginUserHolder.set(systemOperator);
        try {
            doVerify();
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
        log.info("========== 多数据源验证完成 ==========");
    }

    private void doVerify() {

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1. 在 iot 扩展库创建临时验证表（扩展库不入 Flyway）
        DynamicDataSourceContextHolder.push("iot");
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS verify_iot (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            jdbc.execute("INSERT INTO verify_iot (id, name) VALUES (1, 'iot-test-record')");
            log.info("[验证] iot 扩展库 verify_iot 表已创建并插入测试数据");
        } finally {
            DynamicDataSourceContextHolder.poll();
        }

        // 2. 主库查询（默认 master → 租户拦截器应追加 tenant_id = 0）
        log.info("========== 主库查询（预期 SQL 包含 WHERE tenant_id = 0） ==========");
        List<Map<String, Object>> masterRows = verifyMapper.selectFromMaster();
        log.info("[验证] 主库 sys_tenant 查询返回 {} 行", masterRows.size());

        // 3. 扩展库查询（模拟 Service @DS("iot")：先 push 再调 mapper）
        log.info("========== 扩展库查询 iot（预期 SQL 不含 tenant_id） ==========");
        DynamicDataSourceContextHolder.push("iot");
        log.info("[验证] push 后 DS = {}", DynamicDataSourceContextHolder.peek());
        try {
            List<Map<String, Object>> iotRows = verifyMapper.selectFromIot();
            log.info("[验证] 扩展库 verify_iot 查询返回 {} 行, mapper 返回后 DS = {}", iotRows.size(), DynamicDataSourceContextHolder.peek());
        } finally {
            DynamicDataSourceContextHolder.poll();
        }

        // 4. 清理
        DynamicDataSourceContextHolder.push("iot");
        try {
            jdbc.execute("DROP TABLE IF EXISTS verify_iot");
            log.info("[验证] iot 扩展库 verify_iot 表已清理");
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }
}
