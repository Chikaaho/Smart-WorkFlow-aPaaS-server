package com.sw.ck.bootstrap.phase5;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 真实 PostgreSQL 行为证据的公共装载设施。
 *
 * <p>连接参数唯一来源为 Git 工作区外的私有环境文件（Phase 4 方向 §7 规范），
 * 本类只引用 {@code PG_HOST / PG_PORT / PG_USERNAME / PG_PASSWORD} 变量名，
 * 绝不回显其值；对外只暴露脱敏数据库身份。变量不齐时按环境输入缺失跳过，
 * 不向 Owner 再次索要连接值。</p>
 *
 * <p>固定验证数据库：{@code sw_p5_evidence}（本阶段专用，与 Phase 4 的
 * {@code sw_p4_evidence} 及服务器上既有库均不混用）。应用启动前由 Flyway 全链
 * clean + migrate，保证 Phase 5 行为证据运行在生产语义上。</p>
 */
abstract class Phase5PgSupport {

    /** 本阶段固定验证库（专用，不与 Phase 4 证据库或既有库混用）。 */
    static final String EVIDENCE_DB = "sw_p5_evidence";

    /** 全链迁移位置（与 Phase 4 证据装载同构，覆盖 10 个模块）。 */
    static final String[] APP_LOCATIONS = {
            "classpath:db/migration/postgresql",
            "classpath:db/migration/bpm/postgresql",
            "classpath:db/migration/notify/postgresql",
            "classpath:db/migration/form/postgresql",
            "classpath:db/migration/storage/postgresql",
            "classpath:db/migration/job/postgresql",
            "classpath:db/migration/agent/postgresql",
            "classpath:db/migration/iot/postgresql",
            "classpath:db/migration/openapi/postgresql",
            "classpath:db/migration/system/postgresql"
    };

    static final Long TENANT_A = 0L;
    static final Long USER_A = 1L;

    protected ConfigurableApplicationContext app;
    protected JdbcTemplate jdbc;

    // ==================== 环境（只引用变量名） ====================

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            Assumptions.assumeTrue(false,
                    "[P5-PG] 环境变量 " + name + " 未提供（来源：工作区外私有环境文件），"
                            + "本类行为证据按环境输入缺失跳过");
        }
        return value;
    }

    /** 证据库 JDBC URL；变量值不落日志。 */
    static String evidenceUrl() {
        return "jdbc:postgresql://" + env("PG_HOST") + ":" + env("PG_PORT") + "/" + EVIDENCE_DB
                + "?stringtype=unspecified";
    }

    static String adminUrl() {
        return "jdbc:postgresql://" + env("PG_HOST") + ":" + env("PG_PORT") + "/postgres";
    }

    static String pgUser() {
        return env("PG_USERNAME");
    }

    static String pgPassword() {
        return env("PG_PASSWORD");
    }

    /** 脱敏数据库身份：只输出版本与固定库名，供证据包回读。 */
    static String databaseIdentity() {
        try (Connection conn = DriverManager.getConnection(evidenceUrl(), pgUser(), pgPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "select current_setting('server_version') || ' db=' || current_database()")) {
            rs.next();
            return "postgres/" + rs.getString(1) + " (credentials redacted)";
        } catch (Exception e) {
            throw new IllegalStateException("PG 身份读取失败", e);
        }
    }

    /** 固定库缺失时创建（幂等）；不触碰服务器上其他数据库。 */
    static void ensureEvidenceDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(adminUrl(), pgUser(), pgPassword());
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "select 1 from pg_database where datname = '" + EVIDENCE_DB + "'")) {
                if (rs.next()) {
                    System.out.println("[P5-PG] evidence-database=present name=" + EVIDENCE_DB);
                    return;
                }
            }
            stmt.execute("create database " + EVIDENCE_DB);
            System.out.println("[P5-PG] evidence-database=created name=" + EVIDENCE_DB);
        }
    }

    // ==================== 迁移 ====================

    /** 同一 JVM 内只迁移一次：避免每个 Phase 5 测试类都对远端 PostgreSQL 重复 clean+migrate。 */
    private static volatile boolean migrated = false;

    /**
     * 全链 clean + migrate 到终点，同一 JVM 内幂等。
     *
     * <p>Phase 5 的多个 PG 类共用 {@code sw_p5_evidence}，各自使用互不重叠的种子主键与业务键，
     * 因此无需每类重建 schema。次数从 3 次降到 1 次可显著降低远端数据库压力与
     * 长跑中连接被中断的风险（首次全量门禁曾出现一次 SQLState 08006 连接 I/O 中断）。</p>
     */
    static synchronized void cleanMigrateOnce() {
        if (migrated) {
            System.out.println("[P5-PG] flyway-clean-migrate skipped=already-migrated-in-this-jvm db="
                    + EVIDENCE_DB);
            return;
        }
        cleanMigrate();
        migrated = true;
    }

    /** 全链 clean + migrate 到终点（drop + re-migrate，使本阶段行为证据从确定 schema 起跑）。 */
    static MigrateResult cleanMigrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(evidenceUrl(), pgUser(), pgPassword())
                .locations(APP_LOCATIONS)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        MigrateResult result = flyway.migrate();
        String version = flyway.info().current() == null ? "none" : flyway.info().current().getVersion().getVersion();
        System.out.println("[P5-PG] flyway-clean-migrate success=" + result.success
                + " migrationsExecuted=" + result.migrationsExecuted + " targetVersion=" + version);
        assertThat(result.success).isTrue();
        return result;
    }

    // ==================== 应用装载 ====================

    /** 默认测试属性；子类可覆盖。 */
    protected Map<String, Object> properties() {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", evidenceUrl());
        props.put("spring.datasource.dynamic.datasource.master.username", pgUser());
        props.put("spring.datasource.dynamic.datasource.master.password", pgPassword());
        // 迁移由本类显式执行（clean + migrate 原始输出即证据），应用不再自行迁移
        props.put("spring.flyway.enabled", "false");
        props.put("sw.security.jwt.secret", "phase5-pg-test-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", rsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "phase5-pg-test-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.enabled", "true");
        props.put("sw.form.enabled", "true");
        props.put("sw.iot.enabled", "true");
        // 恢复调度默认不自动运行：行为用例显式调用，避免与断言竞争
        props.put("sw.bpm.command.poll-interval-millis", "3600000");
        props.put("sw.bpm.command.p0-poll-interval-millis", "3600000");
        props.put("sw.notify.recovery.fixed-delay-ms", "3600000");
        props.put("sw.iot.trigger.recovery.fixed-delay-ms", "3600000");
        props.put("sw.openapi.callback.recovery.fixed-delay-ms", "3600000");
        return props;
    }

    protected void boot(long tag) {
        boot(tag, Map.of());
    }

    protected void boot(long tag, Map<String, Object> extraProperties) {
        Map<String, Object> props = properties();
        props.putAll(extraProperties);
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("phase5-pg-test-" + tag, props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("phase5PgTestLoginContextProvider" + tag, provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        System.out.println("[P5-PG] application-context=started tag=" + tag
                + " database=" + EVIDENCE_DB + " credentials=redacted");
    }

    protected void shutdown() {
        if (app != null) {
            app.close();
        }
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    // ==================== 断言与查询助手 ====================

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    protected String text(String sql, Object... args) {
        List<String> rows = jdbc.queryForList(sql, String.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected static <T> T asTenant(Long tenantId, Long userId, java.util.concurrent.Callable<T> action) {
        com.sw.ck.security.holder.LoginUser previous = com.sw.ck.security.holder.LoginUserHolder.get();
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        try {
            com.sw.ck.security.holder.LoginUserHolder.set(user);
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) {
                com.sw.ck.security.holder.LoginUserHolder.clear();
            } else {
                com.sw.ck.security.holder.LoginUserHolder.set(previous);
            }
        }
    }

    /** 租户内事务执行：用于证明"业务提交/回滚"边界。 */
    protected <T> T inTransaction(boolean commit, java.util.concurrent.Callable<T> action) {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(
                        app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        return asTenant(TENANT_A, USER_A, () -> template.execute(status -> {
            try {
                T value = action.call();
                if (!commit) {
                    status.setRollbackOnly();
                }
                return value;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    /** 与 Phase 3/4 同构的租户/用户种子。 */
    protected void seedTenantAndUser(long tenantId, long userId, String tag) {
        jdbc.execute("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description)"
                + " VALUES (" + tenantId + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'P5" + tag + "', 'p5-" + tag
                + "', 0, 'phase5 fixture') ON CONFLICT (id) DO NOTHING");
        jdbc.execute("INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, is_admin)"
                + " VALUES (" + userId + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, " + tenantId
                + ", 0, 'p5" + tag + "user', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'P5 user', 0, 0, 1)"
                + " ON CONFLICT (id) DO NOTHING");
    }

    /**
     * 审批状态载体：真实 Flowable 流程。设备命令意图由 t2 节点 create 监听器发布，
     * 因此"审批状态推进"与"持久意图"是否同提交/同回滚可直接由引擎状态与命令行共同观测。
     */
    protected void publishForm(String formKey) {
        com.sw.ck.form.api.dto.FormDefDTO draft =
                app.getBean(com.sw.ck.form.service.FormDefService.class)
                        .createDraft(formKey, "P5-" + formKey, null, null);
        com.sw.ck.form.service.FormDefService service = app.getBean(com.sw.ck.form.service.FormDefService.class);
        service.saveConfig(draft.getId(), """
                {"fields":[{"name":"name","type":"TEXT","label":"名称"}]}
                """);
        service.publish(draft.getId());
    }

    /** 表单↔流程启用绑定。 */
    protected void seedBinding(String formKey, String processDefKey) {
        jdbc.update("insert into sw_bpm_form_binding (id, create_time, update_time, deleted, tenant_id, version,"
                        + " form_key, process_def_key, active) values (?, now(), now(), 0, ?, 0, ?, ?, true)",
                9520L + Math.abs(formKey.hashCode() % 100), TENANT_A, formKey, processDefKey);
        System.out.println("[P5-EV] binding-seeded formKey=" + formKey + " processDefKey=" + processDefKey);
    }

    /** 部署 BPMN 并登记已发布流程定义（真实引擎发起所需）。 */
    protected void deployBpmn(String processKey, String bpmnXml, String formKey, boolean iotAccess) {
        org.flowable.engine.RepositoryService repositoryService =
                app.getBean(org.flowable.engine.RepositoryService.class);
        var deployment = repositoryService.createDeployment()
                .name("p5-" + processKey)
                .tenantId(String.valueOf(TENANT_A))
                .addString(processKey + ".bpmn20.xml", bpmnXml)
                .deploy();
        String definitionId = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult().getId();
        jdbc.update("insert into sw_bpm_process_def (id, create_time, update_time, deleted, tenant_id, version,"
                        + " process_key, name, form_key, def_version, status, deployment_id, process_definition_id,"
                        + " published_version, iot_access_enabled)"
                        + " values (?, now(), now(), 0, ?, 0, ?, ?, ?, 1, 'PUBLISHED', ?, ?, 1, ?)",
                9530L + Math.abs(processKey.hashCode() % 100), TENANT_A, processKey, "P5 " + processKey, formKey,
                deployment.getId(), definitionId, iotAccess);
        System.out.println("[P5-EV] bpmn-deployed processKey=" + processKey
                + " iotAccess=" + iotAccess + " tenant=" + TENANT_A);
    }

    /** 当前活动用户任务 key 列表（判定审批是否单边推进）。 */
    protected List<String> activeTaskKeys(String processInstanceId) {
        return app.getBean(org.flowable.engine.TaskService.class)
                .createTaskQuery().processInstanceId(processInstanceId).list()
                .stream().map(org.flowable.task.api.Task::getTaskDefinitionKey).sorted().toList();
    }

    /** 运行期生成 RSA 私钥，避免任何机器相关路径进入仓库。 */
    private static String rsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
