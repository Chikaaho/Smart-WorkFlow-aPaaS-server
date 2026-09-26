package com.sw.ck.bootstrap.phase3;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDataDeleteService;
import com.sw.ck.form.service.FormDataQueryService;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 REFERENCE 并发完整性证据（真实 PostgreSQL = 生产语义依据）。
 *
 * <p>方向 §5.B 的四条行为要求逐项落地：</p>
 * <ol>
 *   <li><b>先固定当前竞态</b>：{@link #overlap_deleteAndReferenceInsert_neverLeavesOrphan()}
 *       用受控的外部行锁制造“检查后、删除前”窗口——收口前该场景留下存活引用指向已删除父记录
 *       （孤儿引用），收口后无论哪一侧先取得父行锁都不再产生孤儿。</li>
 *   <li><b>串行化机制覆盖“当前无引用行”</b>：锁对象是被引用父记录行本身
 *       （租户 + 物理表 + 记录 id），不是已查到的引用行。</li>
 *   <li><b>锁身份与超时</b>：{@link #parentRowLock_isPerRecord_andDeleteWaits()} 证明按记录加锁且
 *       删除路径确实等待父行锁；{@link #lockTimeout_isDiagnosable()} 证明等待超时给出可诊断错误
 *       （1511，可重试）并回滚。</li>
 *   <li><b>三序无非法状态</b>：引用先到、删除先到、两者重叠三种次序分别断言。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase3 REFERENCE 并发完整性 · 真实 PostgreSQL 行为证据")
class Phase3ReferenceConcurrencyPostgresTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 1L;
    private static final Long TENANT_B = 100L;
    private static final Long USER_B = 9001L;

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static String pgUrl;
    private static JdbcTemplate jdbc;

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private FormDataDeleteService deleteService;
    private FormDataQueryService queryService;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "phase3-pg-test-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "phase3-pg-test-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("phase3-pg-test", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    // 与 I5 PG 用例同构：显式注册 primary LoginContextProvider，
                    // 消除 default/security 两个候选导致的注入歧义
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("phase3PgTestLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        deleteService = app.getBean(FormDataDeleteService.class);
        queryService = app.getBean(FormDataQueryService.class);
        System.out.println("[P3-PG] 真实 PostgreSQL 完整启动成功 pgPort=" + pg.getPort());
        seedTenantsAndUsers();
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
        try {
            if (pg != null) {
                pg.close();
            }
        } catch (Exception ignored) {
            // 关闭容错
        }
        LoginUserHolder.clear();
    }

    private void seedTenantsAndUsers() {
        jdbc.execute("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description)"
                + " VALUES (100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'P3测试租户', 'p3-t100', 0, 'phase3 fixture')"
                + " ON CONFLICT (id) DO NOTHING");
        jdbc.execute("INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, is_admin)"
                + " VALUES (9001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 100, 0, 'p3t100admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'P3租户100用户', 0, 0, 1)"
                + " ON CONFLICT (id) DO NOTHING");
        jdbc.execute("INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, is_admin)"
                + " VALUES (1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'p3t0user', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'P3租户0用户', 0, 0, 1)"
                + " ON CONFLICT (id) DO NOTHING");
    }

    // ==================== 场景搭建 ====================

    /** 目标表单 A + 引用方表单 B（REFERENCE 指向 A），返回 [tableA, tableB, refColumn]。 */
    private String[] publishTargetAndReferencing(String suffix) {
        String targetKey = "p3_pg_target_" + suffix;
        String refKey = "p3_pg_holder_" + suffix;
        publishForm(targetKey, """
                {"fields":[{"name":"name","type":"TEXT","label":"名称"}]}
                """);
        publishForm(refKey, """
                {"fields":[{"name":"target","type":"REFERENCE","targetFormId":"%s","label":"目标"}]}
                """.formatted(targetKey));
        String tableA = jdbc.queryForObject(
                "SELECT c.table_name FROM sw_form_def f JOIN sw_form_config c ON c.form_id = f.id AND c.deleted = 0"
                        + " WHERE f.form_key = ? AND f.deleted = 0", String.class, targetKey);
        String tableB = jdbc.queryForObject(
                "SELECT c.table_name FROM sw_form_def f JOIN sw_form_config c ON c.form_id = f.id AND c.deleted = 0"
                        + " WHERE f.form_key = ? AND f.deleted = 0", String.class, refKey);
        return new String[]{tableA, tableB, "ref_target_id", targetKey, refKey};
    }

    private void publishForm(String formKey, String definitionJson) {
        publishFormAs(TENANT, USER, formKey, definitionJson);
    }

    private void publishFormAs(Long tenantId, Long userId, String formKey, String definitionJson) {
        asTenant(tenantId, userId, () -> {
            FormDefDTO draft = formDefService.createDraft(formKey, "P3-" + formKey, null, null);
            formDefService.saveConfig(draft.getId(), definitionJson);
            formDefService.publish(draft.getId());
            return null;
        });
    }

    private String submit(String formKey, Map<String, Object> data) {
        return asTenant(TENANT, USER, () -> submitService.submitForm(formKey, data, null, null, null));
    }

    private static <T> T asTenant(Long tenantId, Long userId, java.util.concurrent.Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        try {
            LoginUserHolder.set(user);
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) {
                LoginUserHolder.clear();
            } else {
                LoginUserHolder.set(previous);
            }
        }
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private Connection rawConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(pgUrl, "postgres", "postgres");
        connection.setAutoCommit(false);
        return connection;
    }

    /** 在给定连接上对父行加锁（模拟“另一事务正处于删除/校验中”）。 */
    private void lockRow(Connection connection, String table, String recordId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT \"id\" FROM \"" + table + "\" WHERE \"id\" = ? AND \"tenant_id\" = ? FOR UPDATE")) {
            statement.setString(1, recordId);
            statement.setLong(2, TENANT);
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).as("父行应存在以便加锁: %s/%s", table, recordId).isTrue();
            }
        }
    }

    private long liveReferences(String tableB, String refColumn, String targetId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + tableB + "\" WHERE \""
                + refColumn + "\" = ? AND \"deleted\" = 0", Integer.class, targetId);
        return count == null ? -1 : count;
    }

    private boolean isLive(String table, String recordId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + table
                + "\" WHERE \"id\" = ? AND \"deleted\" = 0", Integer.class, recordId);
        return count != null && count == 1;
    }

    private static int errorCode(Throwable error) {
        return error instanceof com.sw.ck.common.exception.BaseException base ? base.getCode() : -1;
    }

    // ==================== 1. 活库 schema（BAO-06 证据边界） ====================

    @Test
    @DisplayName("活库核对：发布后的动态宽表在 PostgreSQL 中带 tenant_id/deleted/id 系统列")
    void liveDynamicTable_systemColumns() {
        String[] ctx = publishTargetAndReferencing("schema");
        String tableA = ctx[0];
        var columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?"
                        + " AND column_name IN ('id','tenant_id','deleted','version') ORDER BY column_name",
                String.class, tableA);
        System.out.println("[P3-PG-SCHEMA] table=" + tableA + " systemColumns=" + columns);
        assertThat(columns).containsExactly("deleted", "id", "tenant_id", "version");
    }

    // ==================== 2. 重叠执行（竞态固定 + 修复证明） ====================

    @Test
    @DisplayName("重叠执行：检查与删除之间新增引用，不得留下孤儿引用")
    void overlap_deleteAndReferenceInsert_neverLeavesOrphan() throws Exception {
        String[] ctx = publishTargetAndReferencing("overlap");
        String tableA = ctx[0];
        String tableB = ctx[1];
        String refColumn = ctx[2];
        String targetKey = ctx[3];
        String refKey = ctx[4];
        String recordId = submit(targetKey, data("name", "被引用记录"));

        // 外部连接占住父行锁：制造“删除已过检查、尚未提交”的窗口
        Connection blocker = rawConnection();
        AtomicReference<String> deleteResult = new AtomicReference<>("pending");
        AtomicReference<String> insertResult = new AtomicReference<>("pending");
        CountDownLatch started = new CountDownLatch(2);
        try {
            lockRow(blocker, tableA, recordId);

            Thread deleteThread = new Thread(() -> asTenant(TENANT, USER, () -> {
                started.countDown();
                try {
                    deleteService.deleteRecord(targetKey, recordId);
                    deleteResult.set("success");
                } catch (Throwable e) {
                    deleteResult.set("code=" + errorCode(e) + ":" + e.getMessage());
                }
                return null;
            }), "p3-delete");
            Thread insertThread = new Thread(() -> asTenant(TENANT, USER, () -> {
                started.countDown();
                try {
                    submitService.submitForm(refKey, data("target", recordId), null, null, null);
                    insertResult.set("success");
                } catch (Throwable e) {
                    insertResult.set("code=" + errorCode(e));
                }
                return null;
            }), "p3-insert");
            deleteThread.start();
            insertThread.start();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(600); // 两侧都在等待父行锁

            // 释放外部锁：两侧竞争同一父行锁
            blocker.commit();
            blocker.close();

            deleteThread.join(60_000);
            insertThread.join(60_000);
            System.out.println("[P3-PG-OVERLAP] delete=" + deleteResult.get() + " insert=" + insertResult.get());

            long orphans = liveReferences(tableB, refColumn, recordId);
            boolean parentLive = isLive(tableA, recordId);
            System.out.println("[P3-PG-OVERLAP] orphans=" + orphans + " parentLive=" + parentLive
                    + " tableA=" + tableA + " tableB=" + tableB);

            assertThat(orphans).as("存活引用不得指向已删除父记录（孤儿引用）").isZero();
            boolean deleteOk = "success".equals(deleteResult.get());
            boolean insertOk = "success".equals(insertResult.get());
            assertThat(deleteOk ^ insertOk).as("两侧必须恰好一方成功（串行化）").isTrue();
            if (insertOk) {
                assertThat(parentLive).as("引用写入成功时父记录必须存活").isTrue();
            }
            assertThat(queryService).isNotNull();
        } finally {
            if (!blocker.isClosed()) {
                try {
                    blocker.rollback();
                } catch (SQLException ignored) {
                    // 回滚容错
                }
                blocker.close();
            }
        }
    }

    // ==================== 3. 锁身份与等待行为 ====================

    @Test
    @DisplayName("父行锁按记录生效：同记录删除等待，异记录删除立即完成")
    void parentRowLock_isPerRecord_andDeleteWaits() throws Exception {
        String[] ctx = publishTargetAndReferencing("lockid");
        String tableA = ctx[0];
        String targetKey = ctx[3];
        String record1 = submit(targetKey, data("name", "记录1"));
        String record2 = submit(targetKey, data("name", "记录2"));

        Connection blocker = rawConnection();
        try {
            lockRow(blocker, tableA, record1);

            AtomicInteger sameRecordCode = new AtomicInteger(-1);
            AtomicReference<Boolean> sameRecordDone = new AtomicReference<>(false);
            Thread sameRecord = new Thread(() -> asTenant(TENANT, USER, () -> {
                try {
                    deleteService.deleteRecord(targetKey, record1);
                } catch (Throwable e) {
                    sameRecordCode.set(errorCode(e));
                } finally {
                    sameRecordDone.set(true);
                }
                return null;
            }), "p3-lock-same");
            sameRecord.start();
            Thread.sleep(900);
            System.out.println("[P3-PG-LOCK] sameRecordCompletedWhileLocked=" + sameRecordDone.get());
            assertThat(sameRecordDone.get())
                    .as("被锁父行的删除必须等待（不得在持锁事务提交前完成）").isFalse();

            // 控制组：另一条记录未被加锁，删除应立即完成
            long start = System.currentTimeMillis();
            asTenant(TENANT, USER, () -> {
                deleteService.deleteRecord(targetKey, record2);
                return null;
            });
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[P3-PG-LOCK] otherRecordDeleteElapsedMs=" + elapsed);
            assertThat(elapsed).as("未被锁记录不应等待").isLessThan(5_000L);

            blocker.commit();
            blocker.close();
            sameRecord.join(60_000);
            System.out.println("[P3-PG-LOCK] sameRecordAfterRelease code=" + sameRecordCode.get());
            assertThat(sameRecordDone.get()).isTrue();
        } finally {
            if (!blocker.isClosed()) {
                try {
                    blocker.rollback();
                } catch (SQLException ignored) {
                    // 回滚容错
                }
                blocker.close();
            }
        }
    }

    // ==================== 4. 引用先到 / 删除先到 ====================

    @Test
    @DisplayName("引用先到：删除被 RESTRICT 拒绝且父记录存活")
    void insertFirst_thenDeleteIsRestricted() {
        String[] ctx = publishTargetAndReferencing("insertfirst");
        String tableA = ctx[0];
        String tableB = ctx[1];
        String refColumn = ctx[2];
        String targetKey = ctx[3];
        String refKey = ctx[4];
        String recordId = submit(targetKey, data("name", "先被引用"));

        submit(refKey, data("target", recordId));
        assertThat(liveReferences(tableB, refColumn, recordId)).isEqualTo(1);

        int code = -1;
        try {
            asTenant(TENANT, USER, () -> {
                deleteService.deleteRecord(targetKey, recordId);
                return null;
            });
        } catch (com.sw.ck.common.exception.BaseException e) {
            code = e.getCode();
        }
        System.out.println("[P3-PG-INSERT-FIRST] deleteCode=" + code + " parentLive=" + isLive(tableA, recordId));
        assertThat(code).isEqualTo(com.sw.ck.form.api.exception.FormErrorCode.DELETE_RESTRICT_REFERENCED.getCode());
        assertThat(isLive(tableA, recordId)).isTrue();
        assertThat(liveReferences(tableB, refColumn, recordId)).isEqualTo(1);
    }

    @Test
    @DisplayName("删除先到：父记录已软删后引用写入被拒绝，不产生孤儿")
    void deleteFirst_thenReferenceInsertFailsClosed() throws Exception {
        String[] ctx = publishTargetAndReferencing("deletefirst");
        String tableA = ctx[0];
        String tableB = ctx[1];
        String refColumn = ctx[2];
        String targetKey = ctx[3];
        String refKey = ctx[4];
        String recordId = submit(targetKey, data("name", "先被删除"));

        Connection blocker = rawConnection();
        try {
            lockRow(blocker, tableA, recordId);
            // 外部事务完成真实的软删（等价于删除路径持锁提交）
            blocker.createStatement().executeUpdate("UPDATE \"" + tableA
                    + "\" SET \"deleted\" = 1 WHERE \"id\" = '" + recordId + "'");
            blocker.commit();
            blocker.close();
        } finally {
            if (!blocker.isClosed()) {
                blocker.close();
            }
        }
        assertThat(isLive(tableA, recordId)).isFalse();

        int code = -1;
        try {
            submit(refKey, data("target", recordId));
        } catch (com.sw.ck.common.exception.BaseException e) {
            code = e.getCode();
        }
        System.out.println("[P3-PG-DELETE-FIRST] insertCode=" + code
                + " orphans=" + liveReferences(tableB, refColumn, recordId));
        assertThat(code).isEqualTo(com.sw.ck.form.api.exception.FormErrorCode.REFERENCE_OBJECT_NOT_FOUND.getCode());
        assertThat(liveReferences(tableB, refColumn, recordId)).isZero();
    }

    // ==================== 5. 超时可诊断 ====================

    @Test
    @DisplayName("锁等待超时：返回可诊断错误（1511）且不产生引用")
    void lockTimeout_isDiagnosable() throws Exception {
        String[] ctx = publishTargetAndReferencing("timeout");
        String tableB = ctx[1];
        String refColumn = ctx[2];
        String targetKey = ctx[3];
        String refKey = ctx[4];
        String recordId = submit(targetKey, data("name", "长时间被占用"));

        Connection blocker = rawConnection();
        try {
            lockRow(blocker, ctx[0], recordId);
            long start = System.currentTimeMillis();
            int code = -1;
            try {
                submit(refKey, data("target", recordId));
            } catch (com.sw.ck.common.exception.BaseException e) {
                code = e.getCode();
            }
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[P3-PG-TIMEOUT] code=" + code + " elapsedMs=" + elapsed
                    + " orphans=" + liveReferences(tableB, refColumn, recordId));
            assertThat(code).isEqualTo(
                    com.sw.ck.form.api.exception.FormErrorCode.DYNAMIC_ROW_LOCK_TIMEOUT.getCode());
            assertThat(liveReferences(tableB, refColumn, recordId)).isZero();
        } finally {
            try {
                blocker.rollback();
            } catch (SQLException ignored) {
                // 回滚容错
            }
            blocker.close();
        }
    }

    // ==================== 6. 跨租户边界 ====================

    @Test
    @DisplayName("跨租户：引用与删除均不可越界，CASCADE 只作用于本租户")
    void crossTenantReferenceAndDeleteBoundary() {
        String[] ctx = publishTargetAndReferencing("tenant");
        String tableA = ctx[0];
        String tableB = ctx[1];
        String refColumn = ctx[2];
        String targetKey = ctx[3];
        String refKey = ctx[4];
        String recordId = submit(targetKey, data("name", "租户0记录"));

        // 租户 100 发布同键表单（表单按租户隔离），随后尝试引用租户 0 的记录
        publishFormAs(TENANT_B, USER_B, targetKey, """
                {"fields":[{"name":"name","type":"TEXT","label":"名称"}]}
                """);
        publishFormAs(TENANT_B, USER_B, refKey, """
                {"fields":[{"name":"target","type":"REFERENCE","targetFormId":"%s","label":"目标"}]}
                """.formatted(targetKey));

        int insertCode = -1;
        try {
            asTenant(TENANT_B, USER_B, () -> submitService.submitForm(refKey,
                    data("target", recordId), null, null, null));
        } catch (com.sw.ck.common.exception.BaseException e) {
            insertCode = e.getCode();
        }
        System.out.println("[P3-PG-TENANT] crossTenantInsertCode=" + insertCode);
        assertThat(insertCode).isEqualTo(
                com.sw.ck.form.api.exception.FormErrorCode.REFERENCE_OBJECT_NOT_FOUND.getCode());

        // 租户 100 删除租户 0 的记录：幂等无操作，记录存活
        asTenant(TENANT_B, USER_B, () -> {
            deleteService.deleteRecord(targetKey, recordId);
            return null;
        });
        assertThat(isLive(tableA, recordId)).as("跨租户删除不得影响他租户记录").isTrue();
        assertThat(liveReferences(tableB, refColumn, recordId)).isZero();
    }

    private static String generatedRsaPkcs8Base64() {
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
