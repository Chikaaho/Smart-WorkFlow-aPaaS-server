package com.sw.ck.bootstrap;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.SecurityLoginContextProvider;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.service.TenantAdminService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T020：正式迁移、真实 Mapper/租户拦截器与 Spring 事务代理的 H2/PG 行为。 */
class TenantAdministrationDatabaseTest {
    enum Database { H2, POSTGRES }

    @AfterEach
    void clearIdentity() { LoginUserHolder.clear(); }

    @ParameterizedTest
    @EnumSource(Database.class)
    void realBpmQueuePausesPendingTenantAndResumesOriginalCommand(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false)) {
            rootIdentity();
            Long tenantId = f.service.create(command());
            Long adminId = f.service.detail(tenantId).adminUserId();
            long commandId = 882210L;
            f.jdbc.update("INSERT INTO sw_bpm_command (id, tenant_id, command_key, command_type, channel, status, payload, retry_count, initiator_id, deleted, create_time) VALUES (?, ?, ?, 'TASK_APPROVE', 'NORMAL', 'PENDING', '{}', 0, ?, 0, ?)",
                    commandId, tenantId, "t020-controlled-claim", adminId, java.time.LocalDateTime.now());
            f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
            assertThat(f.commandQueue.claimDue(java.util.List.of(com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL), 10)).isEmpty();
            assertThat(f.jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId)).isEqualTo("PENDING");
            assertThat(f.jdbc.queryForObject("SELECT claim_token FROM sw_bpm_command WHERE id = ?", String.class, commandId)).isNull();
            f.jdbc.update("UPDATE sys_tenant SET status = 0 WHERE id = ?", tenantId);
            var claimed = f.commandQueue.claimDue(java.util.List.of(com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL), 10);
            assertThat(claimed).hasSize(1);
            assertThat(claimed.getFirst().getCommandId()).isEqualTo(commandId);
            assertThat(claimed.getFirst().getTenantId()).isEqualTo(tenantId);
            assertThat(claimed.getFirst().getClaimToken()).isNotBlank();
            f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
            // 已持久认领可沿原租约记录真实结果；其他待执行工作仍不能进入新认领。
            f.commandQueue.complete(commandId, claimed.getFirst().getClaimToken(), "{\"controlled\":true}");
            assertThat(f.jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId)).isEqualTo("COMPLETED");
            f.jdbc.update("UPDATE sys_tenant SET status = 0 WHERE id = ?", tenantId);
            assertThat(f.commandQueue.claimDue(java.util.List.of(com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL), 10)).isEmpty();
            assertThat(f.count("SELECT COUNT(*) FROM sw_bpm_command WHERE command_key = ?", "t020-controlled-claim")).isEqualTo(1);
            assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(0L);
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void tenantDisableAndPersistentClaimHaveAnOrderedBoundary(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false); var executor = Executors.newFixedThreadPool(2)) {
            rootIdentity();
            Long tenantId = f.service.create(command());
            f.jdbc.execute("CREATE TABLE t020_admission_work (id INT PRIMARY KEY, state VARCHAR(32))");
            f.jdbc.update("INSERT INTO t020_admission_work VALUES (1, 'PENDING'), (2, 'PENDING')");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var finishClaim = new java.util.concurrent.CountDownLatch(1);
            var disableStarted = new java.util.concurrent.CountDownLatch(1);
            var claimed = executor.submit(() -> f.workAdmission.claim(tenantId, () -> {
                entered.countDown();
                try {
                    if (!finishClaim.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("claim release missing");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return f.jdbc.update("UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 1 AND state = 'PENDING'");
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var disabled = executor.submit(() -> {
                disableStarted.countDown();
                return f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
            });
            try {
                assertThat(disableStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> disabled.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { finishClaim.countDown(); }
            assertThat(claimed.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(disabled.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            assertThat(f.workAdmission.claim(tenantId, () -> {
                attempts.incrementAndGet();
                return f.jdbc.update("UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 2");
            })).isZero();
            assertThat(attempts).hasValue(0);
            assertThat(f.jdbc.queryForObject("SELECT state FROM t020_admission_work WHERE id = 2", String.class)).isEqualTo("PENDING");
            // 已认领动作可真实结束；停用不伪造其他对象状态。
            assertThat(f.jdbc.update("UPDATE t020_admission_work SET state = 'DONE' WHERE id = 1 AND state = 'PROCESSING'")).isEqualTo(1);
            f.jdbc.update("UPDATE sys_tenant SET status = 0, expire_time = ? WHERE id = ?",
                    java.time.LocalDateTime.now().minusMinutes(1), tenantId);
            assertThat(f.workAdmission.claim(tenantId, () -> { attempts.incrementAndGet(); return 1; })).isZero();
            f.jdbc.update("UPDATE sys_tenant SET expire_time = NULL WHERE id = ?", tenantId);
            assertThatThrownBy(() -> f.workAdmission.claim(tenantId, () -> {
                f.jdbc.update("UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 2");
                throw new IllegalStateException("controlled failure after write");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(f.jdbc.queryForObject("SELECT state FROM t020_admission_work WHERE id = 2", String.class)).isEqualTo("PENDING");
            assertThat(f.workAdmission.claim(tenantId, () -> f.jdbc.update(
                    "UPDATE t020_admission_work SET state = 'PROCESSING' WHERE id = 2 AND state = 'PENDING'"))).isEqualTo(1);
            assertThat(f.workAdmission.claim(null, () -> { attempts.incrementAndGet(); return 1; })).isZero();
            assertThat(attempts).hasValue(0);
            assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(0L);
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void cachedSessionAdmissionReadsCurrentTenantStatusAndExpiry(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false)) {
            rootIdentity();
            Long tenantId = f.service.create(command());
            Long adminId = f.service.detail(tenantId).adminUserId();
            LoginUser cached = new LoginUser();
            cached.setUserId(adminId);
            cached.setTenantId(tenantId);
            var provider = new com.sw.ck.system.security.UserDetailsProviderImpl(
                    f.users, null, null, null, null, null, f.validity);
            // 身份缓存未变；每次准入读取真实租户行，不依赖清理 Redis 的时机。
            assertThat(provider.isCachedIdentityValid(cached)).isTrue();
            f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
            assertThat(provider.isCachedIdentityValid(cached)).isFalse();
            f.jdbc.update("UPDATE sys_tenant SET status = 0, expire_time = ? WHERE id = ?",
                    java.time.LocalDateTime.now().minusMinutes(1), tenantId);
            assertThat(provider.isCachedIdentityValid(cached)).isFalse();
            f.jdbc.update("UPDATE sys_tenant SET expire_time = ? WHERE id = ?",
                    java.time.LocalDateTime.now().plusDays(1), tenantId);
            assertThat(provider.isCachedIdentityValid(cached)).isTrue();
            f.jdbc.update("UPDATE sys_tenant SET deleted = 1 WHERE id = ?", tenantId);
            assertThat(provider.isCachedIdentityValid(cached)).isFalse();
            cached.setTenantId(null);
            assertThat(provider.isCachedIdentityValid(cached)).isFalse();
            assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(0L);
            assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ?", tenantId)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void creationIsStableAcrossRetryRenameAndAnotherAdministrator(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false)) {
            rootIdentity();
            var command = command();
            Long tenantId = f.service.create(command);
            Long adminId = f.service.detail(tenantId).adminUserId();
            assertThat(adminId).isNotNull();
            assertThat(f.encoder.matches("test-only-password", f.jdbc.queryForObject(
                    "SELECT password FROM sys_user WHERE id = ?", String.class, adminId))).isTrue();
            f.jdbc.update("UPDATE sys_user SET username = 'renamed_admin' WHERE id = ?", adminId);
            addAdmin(f, tenantId, 88101L, 88102L, "second_admin");
            assertThat(f.service.detail(tenantId).adminUsername()).isEqualTo("renamed_admin");
            assertThat(f.service.create(command)).isEqualTo(tenantId);
            assertThat(f.count("SELECT COUNT(*) FROM sys_tenant WHERE creation_request_id = ?", command.requestId())).isEqualTo(1);
            assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ?", tenantId)).isEqualTo(2);
            assertThat(f.jdbc.queryForObject("SELECT leader_id FROM sys_dept WHERE tenant_id = ?", Long.class, tenantId)).isEqualTo(adminId);
            assertThat(f.service.adminCandidates(tenantId)).extracting(TenantAdminService.AdminCandidate::id)
                    .containsExactlyInAnyOrder(adminId, 88101L);
            assertThat(f.jdbc.queryForObject("SELECT component FROM sys_menu WHERE id = 9600", String.class))
                    .isEqualTo("system/views/TenantList");
            assertThat(f.jdbc.queryForObject("SELECT path FROM sys_menu WHERE id = 9600", String.class))
                    .isEqualTo("system/tenant");
            assertThat(f.count("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id WHERE rm.menu_id = 9600 AND r.code = 'superadmin' AND r.tenant_id = 0"))
                    .isEqualTo(1);
            assertThat(f.count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 9600 AND tenant_id = ?", tenantId)).isZero();
            System.out.println("[T020-" + database + "] stable tenant=" + tenantId + ", admin=" + adminId + ", retry returned original");
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void concurrentRetryCreatesOneCompleteTenant(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false); var executor = Executors.newFixedThreadPool(2)) {
            var command = command();
            var barrier = new CyclicBarrier(2);
            java.util.concurrent.Callable<Long> create = () -> {
                rootIdentity();
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    return f.service.create(command);
                } finally { LoginUserHolder.clear(); }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            Long tenantId = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(tenantId);
            assertThat(f.count("SELECT COUNT(*) FROM sys_tenant WHERE creation_request_id = ?", command.requestId())).isEqualTo(1);
            assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            assertThat(f.count("SELECT COUNT(*) FROM sys_dept WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            assertThat(f.count("SELECT COUNT(*) FROM sys_role WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            assertThat(f.count("SELECT COUNT(*) FROM sys_user_role WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            System.out.println("[T020-" + database + "] concurrent retry: one tenant, user, department, role and binding");
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void initializationFailureRollsBackAndSameRequestCanRetry(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false)) {
            rootIdentity();
            var command = command();
            long tenants = f.count("SELECT COUNT(*) FROM sys_tenant");
            long users = f.count("SELECT COUNT(*) FROM sys_user");
            long departments = f.count("SELECT COUNT(*) FROM sys_dept");
            f.jdbc.update("UPDATE sys_role SET status = 0 WHERE tenant_id = 0 AND code = 'admin'");
            assertThatThrownBy(() -> f.service.create(command)).isInstanceOf(BaseException.class).hasMessageContaining("模板已停用");
            assertThat(f.count("SELECT COUNT(*) FROM sys_tenant")).isEqualTo(tenants);
            assertThat(f.count("SELECT COUNT(*) FROM sys_user")).isEqualTo(users);
            assertThat(f.count("SELECT COUNT(*) FROM sys_dept")).isEqualTo(departments);
            f.jdbc.update("UPDATE sys_role SET status = 1 WHERE tenant_id = 0 AND code = 'admin'");
            Long id = f.service.create(command);
            assertThat(f.service.detail(id).adminUserId()).isNotNull();
            System.out.println("[T020-" + database + "] late initialization failure: zero partial rows, same key retry succeeded");
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void legacyMaintenanceValidatesTargetAndDoesNotCreateAnotherFirstAdmin(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false)) {
            rootIdentity();
            addTenant(f, 88L);
            Long adminId = f.service.createAdministrator(88L, "legacy_admin", "test-only-password");
            assertThat(f.service.detail(88L).adminUserId()).isEqualTo(adminId);
            assertThatThrownBy(() -> f.service.createAdministrator(88L, "another_admin", "test-only-password"))
                    .isInstanceOf(BaseException.class).hasMessageContaining("已配置管理员");
            Long otherTenant = f.service.create(command());
            Long foreignAdmin = f.service.detail(otherTenant).adminUserId();
            assertThatThrownBy(() -> f.service.assignAdministrator(88L, foreignAdmin))
                    .isInstanceOf(BaseException.class).hasMessageContaining("该租户内");
            assertThat(f.service.detail(88L).adminUserId()).isEqualTo(adminId);
            addAdmin(f, 88L, 88101L, 88102L, "replacement_admin");
            f.service.assignAdministrator(88L, 88101L);
            assertThat(f.service.detail(88L).adminUserId()).isEqualTo(88101L);
            assertThatThrownBy(() -> f.service.assignAdministrator(0L, adminId))
                    .isInstanceOf(BaseException.class).hasMessageContaining("保留对象");
            LoginUserHolder.get().setSuperAdmin(false);
            LoginUserHolder.get().setTenantId(88L);
            assertThatThrownBy(() -> f.service.adminCandidates(88L)).isInstanceOf(BaseException.class).hasMessageContaining("超级管理员");
            assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE tenant_id = 88")).isEqualTo(2);
            System.out.println("[T020-" + database + "] legacy maintenance: stable assignment, cross-tenant/root/ordinary denial, no duplicate first admin");
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void migrationBackfillsOnlyAnUnambiguousAuthorizedAdministrator(Database database) throws Exception {
        try (Fixture f = new Fixture(database, true)) {
            addTenant(f, 88L);
            addTenant(f, 89L);
            addAdmin(f, 88L, 88101L, 88102L, "unique_admin");
            addAdmin(f, 89L, 89101L, 89102L, "ambiguous_admin_a");
            addAdmin(f, 89L, 89103L, 89104L, "ambiguous_admin_b");
            // A corrupt cross-tenant role binding must not count as an authorized same-tenant administrator.
            f.jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id) VALUES (88999, 89101, 88102, 88)");
            f.flyway(null).migrate();
            assertThat(f.jdbc.queryForObject("SELECT admin_user_id FROM sys_tenant WHERE id = 88", Long.class)).isEqualTo(88101L);
            assertThat(f.jdbc.queryForObject("SELECT admin_user_id FROM sys_tenant WHERE id = 89", Long.class)).isNull();
            assertThat(f.flyway(null).migrate().migrationsExecuted).isZero();
            System.out.println("[T020-" + database + "] migration: unique association backfilled; ambiguous and foreign bindings not promoted");
        }
    }

    private static TenantAdminService.CreateTenantCommand command() {
        return new TenantAdminService.CreateTenantCommand("数据库验证租户", "initial_admin", "test-only-password", UUID.randomUUID().toString());
    }

    private static void rootIdentity() {
        LoginUser login = new LoginUser();
        login.setUserId(1L); login.setUsername("admin"); login.setTenantId(0L); login.setSuperAdmin(true);
        LoginUserHolder.set(login);
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void managementSeparatesActorAndTargetAndRejectsForeignAssociations(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false)) {
            rootIdentity();
            assertThat(f.users.getById(1L).isProtectedAccount()).isTrue();
            var reserved = managementUser(0L, null, "reserved_edit"); reserved.setId(1L);
            assertThatThrownBy(() -> f.userController.update(reserved)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> f.userController.delete(1L)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> f.users.updateRoleIds(1L, java.util.List.of())).isInstanceOf(BaseException.class);
            assertThat(f.jdbc.queryForObject("SELECT username FROM sys_user WHERE id = 1", String.class)).isEqualTo("admin");
            Long first = f.service.create(command());
            Long second = f.service.create(command());
            Long firstRoot = f.jdbc.queryForObject("SELECT id FROM sys_dept WHERE tenant_id = ?", Long.class, first);
            Long secondRoot = f.jdbc.queryForObject("SELECT id FROM sys_dept WHERE tenant_id = ?", Long.class, second);
            Long firstRole = f.jdbc.queryForObject("SELECT id FROM sys_role WHERE tenant_id = ?", Long.class, first);
            Long secondRole = f.jdbc.queryForObject("SELECT id FROM sys_role WHERE tenant_id = ?", Long.class, second);
            f.jdbc.update("INSERT INTO sys_post (id, tenant_id, code, name, status) VALUES (99101, ?, 'target_post', '本租户岗位', 1)", first);
            f.jdbc.update("INSERT INTO sys_post (id, tenant_id, code, name, status) VALUES (99102, ?, 'target_post', '另一租户岗位', 1)", second);

            var department = new com.sw.ck.system.entity.SysDept();
            department.setTenantId(first); department.setParentId(firstRoot);
            department.setName("目标部门"); department.setCode("target_dept");
            Long departmentId = f.deptController.create(department).getData();
            assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_dept WHERE id = ?", Long.class, departmentId)).isEqualTo(first);
            assertThat(f.jdbc.queryForObject("SELECT create_by FROM sys_dept WHERE id = ?", Long.class, departmentId)).isEqualTo(1L);
            assertThat(LoginUserHolder.get().getTenantId()).isZero();

            var request = managementUser(first, departmentId, "shared_username");
            request.setRoleIds(java.util.List.of(firstRole));
            request.setPosts(java.util.List.of(new com.sw.ck.system.controller.UserController.PostAssignment(99101L, departmentId)));
            Long userId = f.userController.create(request).getData();
            assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user WHERE id = ?", Long.class, userId)).isEqualTo(first);
            assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user_role WHERE user_id = ? AND deleted = 0", Long.class, userId)).isEqualTo(first);
            assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user_post WHERE user_id = ? AND deleted = 0", Long.class, userId)).isEqualTo(first);
            f.users.updateRoleIds(userId, java.util.List.of());
            f.users.updateRoleIds(userId, java.util.List.of(firstRole));
            f.users.updateRoleIds(userId, java.util.List.of(firstRole));
            assertThat(f.count("SELECT COUNT(*) FROM sys_user_role WHERE user_id = ? AND tenant_id = ? AND deleted = 0", userId, first)).isEqualTo(1);
            f.userController.create(managementUser(second, secondRoot, "shared_username"));
            assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE username = 'shared_username' AND deleted = 0")).isEqualTo(2);

            var missing = managementUser(null, null, "missing_target");
            assertThatThrownBy(() -> f.userController.create(missing)).isInstanceOf(BaseException.class);
            assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE username = 'missing_target'")).isZero();
            var foreignDept = managementUser(first, secondRoot, "foreign_department");
            assertThatThrownBy(() -> f.userController.create(foreignDept)).isInstanceOf(BaseException.class);
            var foreignRole = managementUser(first, departmentId, "foreign_role");
            foreignRole.setRoleIds(java.util.List.of(secondRole));
            assertThatThrownBy(() -> f.userController.create(foreignRole)).isInstanceOf(IllegalArgumentException.class);
            var foreignPost = managementUser(first, departmentId, "foreign_post");
            foreignPost.setPosts(java.util.List.of(new com.sw.ck.system.controller.UserController.PostAssignment(99102L, departmentId)));
            assertThatThrownBy(() -> f.userController.create(foreignPost)).isInstanceOf(IllegalArgumentException.class);
            assertThat(f.count("SELECT COUNT(*) FROM sys_user WHERE username IN ('foreign_department','foreign_role','foreign_post')")).isZero();
            request.setId(userId); request.setTenantId(second);
            assertThatThrownBy(() -> f.userController.update(request)).isInstanceOf(BaseException.class);
            assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user WHERE id = ?", Long.class, userId)).isEqualTo(first);
            var badParent = new com.sw.ck.system.entity.SysDept();
            badParent.setTenantId(first); badParent.setParentId(secondRoot); badParent.setName("跨租户部门"); badParent.setCode("foreign_parent");
            assertThatThrownBy(() -> f.deptController.create(badParent)).isInstanceOf(BaseException.class);
            var badLeader = new com.sw.ck.system.entity.SysDept();
            badLeader.setTenantId(first); badLeader.setParentId(firstRoot); badLeader.setName("错误负责人"); badLeader.setCode("foreign_leader");
            badLeader.setLeaderId(f.service.detail(second).adminUserId());
            assertThatThrownBy(() -> f.deptController.create(badLeader)).isInstanceOf(BaseException.class);
            department.setTenantId(second);
            assertThatThrownBy(() -> f.deptController.update(department)).isInstanceOf(BaseException.class);
            assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_dept WHERE id = ?", Long.class, departmentId)).isEqualTo(first);

            var query = new com.sw.ck.system.service.UserPageQuery(); query.setTenantId(first);
            var page = f.userController.page(1, 200, query).getData();
            assertThat(page.getRecords()).allMatch(row -> first.equals(row.getTenantId()));
            assertThat(page.getRecords()).allMatch(row -> row.getTenantName() != null);
            assertThat(f.deptController.tree(null, null, first).getData()).allMatch(row -> first.equals(row.getTenantId()));
            assertThat(LoginUserHolder.get().getTenantId()).isZero();

            LoginUser actor = new LoginUser(); actor.setTenantId(first); actor.setUserId(userId); LoginUserHolder.set(actor);
            Long ordinaryId = f.userController.create(managementUser(null, departmentId, "ordinary_own")).getData();
            assertThat(f.jdbc.queryForObject("SELECT tenant_id FROM sys_user WHERE id = ?", Long.class, ordinaryId)).isEqualTo(first);
            assertThatThrownBy(() -> f.userController.create(managementUser(second, secondRoot, "ordinary_foreign"))).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> f.deptController.tree(null, null, second)).isInstanceOf(BaseException.class);
            query.setTenantId(second);
            assertThatThrownBy(() -> f.userController.page(1, 200, query)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> f.management.options(null, false)).isInstanceOf(BaseException.class);
            assertThat(LoginUserHolder.get().getTenantId()).isEqualTo(first);
            System.out.println("[T020-" + database + "] management actor unchanged, explicit target, same-tenant associations, rollback and ordinary isolation");
        }
    }

    @ParameterizedTest
    @EnumSource(Database.class)
    void managementCreationOptionsExcludeDisabledExpiredAndMissingTargets(Database database) throws Exception {
        try (Fixture f = new Fixture(database, false)) {
            rootIdentity();
            Long tenantId = f.service.create(command());
            f.jdbc.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", tenantId);
            assertThat(f.management.options(null, true)).noneMatch(row -> row.id().equals(tenantId));
            assertThat(f.management.options(null, false)).anyMatch(row -> row.id().equals(tenantId) && !row.writable());
            assertThatThrownBy(() -> f.management.createTarget(tenantId)).isInstanceOf(BaseException.class);
            f.jdbc.update("UPDATE sys_tenant SET status = 0, expire_time = ? WHERE id = ?", java.time.LocalDateTime.now().minusSeconds(5), tenantId);
            assertThat(f.management.options(null, true)).noneMatch(row -> row.id().equals(tenantId));
            assertThatThrownBy(() -> f.management.createTarget(tenantId)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> f.management.createTarget(999999L)).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> f.management.createTarget(null)).isInstanceOf(BaseException.class);
            f.jdbc.update("UPDATE sys_tenant SET expire_time = null WHERE id = ?", tenantId);
            assertThat(f.management.createTarget(tenantId)).isEqualTo(tenantId);
            assertThat(f.management.options(null, true)).anyMatch(row -> row.id().equals(tenantId) && row.writable());
            System.out.println("[T020-" + database + "] management target validity: disabled, expired, missing rejected; restored tenant selectable");
        }
    }

    private static com.sw.ck.system.controller.UserController.UserFormRequest managementUser(Long tenantId, Long deptId, String username) {
        var request = new com.sw.ck.system.controller.UserController.UserFormRequest();
        request.setTenantId(tenantId); request.setDeptId(deptId); request.setUsername(username);
        request.setPlainPassword("test-only-password"); request.setStatus(0);
        return request;
    }

    private static void addTenant(Fixture f, long id) {
        f.jdbc.update("INSERT INTO sys_tenant (id, name, code, status) VALUES (?, ?, ?, 0)", id, "Legacy " + id, "legacy-" + id);
    }

    private static void addAdmin(Fixture f, long tenantId, long userId, long roleId, String username) {
        f.jdbc.update("INSERT INTO sys_user (id, username, password, status, tenant_id) VALUES (?, ?, ?, 0, ?)", userId, username, f.encoder.encode("test-only-password"), tenantId);
        var existingRoles = f.jdbc.queryForList("SELECT id FROM sys_role WHERE tenant_id = ? AND code = 'admin' AND deleted = 0", Long.class, tenantId);
        long targetRoleId = existingRoles.isEmpty() ? roleId : existingRoles.get(0);
        if (existingRoles.isEmpty()) {
            f.jdbc.update("INSERT INTO sys_role (id, name, code, status, tenant_id) VALUES (?, '管理员', 'admin', 1, ?)", roleId, tenantId);
        }
        f.jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id) VALUES (?, ?, ?, ?)", userId, userId, targetRoleId, tenantId);
    }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedPostgres postgres;
        final DriverManagerDataSource dataSource;
        final JdbcTemplate jdbc;
        final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        final TenantAdminService service;
        final String vendor;
        final com.sw.ck.system.service.SysUserService users;
        final com.sw.ck.system.service.SysDeptService departments;
        final com.sw.ck.system.service.ManagementTenantContext management;
        final com.sw.ck.system.service.TenantValidityService validity;
        final com.sw.ck.system.service.TenantWorkAdmissionService workAdmission;
        final com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue commandQueue;
        final com.sw.ck.system.controller.UserController userController;
        final com.sw.ck.system.controller.DeptController deptController;

        Fixture(Database database, boolean oldVersion) throws Exception {
            vendor = database == Database.H2 ? "h2" : "postgresql";
            if (database == Database.POSTGRES) {
                postgres = EmbeddedPostgres.builder().start();
                dataSource = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
                var connectionProperties = new java.util.Properties();
                connectionProperties.setProperty("socketTimeout", "15");
                connectionProperties.setProperty("options", "-c lock_timeout=5000 -c statement_timeout=10000");
                dataSource.setConnectionProperties(connectionProperties);
            } else {
                postgres = null;
                dataSource = new DriverManagerDataSource("jdbc:h2:mem:tenant_identity_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
            }
            jdbc = new JdbcTemplate(dataSource);
            try {
                flyway(oldVersion ? "0.1.8" : null).migrate();
                var context = new SecurityLoginContextProvider();
                var global = new GlobalConfig();
                global.setDbConfig(new GlobalConfig.DbConfig().setLogicDeleteField("deleted").setLogicDeleteValue("1").setLogicNotDeleteValue("0"));
                global.setMetaObjectHandler(new CommonMetaObjectHandler(context));
                var config = new MybatisConfiguration();
                config.setMapUnderscoreToCamelCase(true);
                var interceptor = new MybatisPlusInterceptor();
                interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new CommonTenantLineHandler(new TenantProperties(), context)));
                interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
                interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(
                        database == Database.H2 ? com.baomidou.mybatisplus.annotation.DbType.H2 : com.baomidou.mybatisplus.annotation.DbType.POSTGRE_SQL));
                var factory = new MybatisSqlSessionFactoryBean();
                factory.setDataSource(dataSource); factory.setConfiguration(config); factory.setGlobalConfig(global); factory.setPlugins(interceptor);
                var sessions = factory.getObject();
                for (Class<?> mapper : new Class<?>[]{SysTenantMapper.class, SysDeptMapper.class, SysUserMapper.class, SysRoleMapper.class, SysRoleMenuMapper.class, SysUserRoleMapper.class,
                        com.sw.ck.system.mapper.SysPostMapper.class, com.sw.ck.system.mapper.SysUserPostMapper.class,
                        com.sw.ck.bpm.process.mapper.BpmCommandMapper.class}) {
                    sessions.getConfiguration().addMapper(mapper);
                }
                var template = new SqlSessionTemplate(sessions);
                var transactions = new DataSourceTransactionManager(dataSource);
                workAdmission = new com.sw.ck.system.service.TenantWorkAdmissionService(template.getMapper(SysTenantMapper.class), transactions);
                var commandService = new com.sw.ck.bpm.process.service.impl.BpmCommandServiceImpl();
                org.springframework.test.util.ReflectionTestUtils.setField(commandService, "baseMapper",
                        template.getMapper(com.sw.ck.bpm.process.mapper.BpmCommandMapper.class));
                var resourceAdmission = org.mockito.Mockito.mock(com.sw.ck.bpm.process.service.ResourceAdmissionService.class);
                commandQueue = new com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue(commandService, null,
                        org.mockito.Mockito.mock(com.sw.ck.bpm.process.service.ResourceReleaseService.class), resourceAdmission, workAdmission);
                var target = new TenantAdminService(template.getMapper(SysTenantMapper.class), template.getMapper(SysDeptMapper.class),
                        template.getMapper(SysUserMapper.class), template.getMapper(SysRoleMapper.class), template.getMapper(SysRoleMenuMapper.class),
                        template.getMapper(SysUserRoleMapper.class), encoder, transactions);
                var proxy = new ProxyFactory(target);
                proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
                service = (TenantAdminService) proxy.getProxy();
                var userTarget = new com.sw.ck.system.service.impl.SysUserServiceImpl(encoder,
                        template.getMapper(SysUserRoleMapper.class), template.getMapper(com.sw.ck.system.mapper.SysUserPostMapper.class),
                        template.getMapper(SysRoleMapper.class), template.getMapper(com.sw.ck.system.mapper.SysPostMapper.class),
                        template.getMapper(SysDeptMapper.class), null);
                org.springframework.test.util.ReflectionTestUtils.setField(userTarget, "baseMapper", template.getMapper(SysUserMapper.class));
                var userProxy = new ProxyFactory(userTarget);
                userProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
                users = (com.sw.ck.system.service.SysUserService) userProxy.getProxy();
                var deptTarget = new com.sw.ck.system.service.impl.SysDeptServiceImpl(users);
                org.springframework.test.util.ReflectionTestUtils.setField(deptTarget, "baseMapper", template.getMapper(SysDeptMapper.class));
                var deptProxy = new ProxyFactory(deptTarget);
                deptProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
                departments = (com.sw.ck.system.service.SysDeptService) deptProxy.getProxy();
                validity = new com.sw.ck.system.service.TenantValidityService(template.getMapper(SysTenantMapper.class));
                management = new com.sw.ck.system.service.ManagementTenantContext(template.getMapper(SysTenantMapper.class), validity);
                userController = new com.sw.ck.system.controller.UserController(users, null, management);
                deptController = new com.sw.ck.system.controller.DeptController(departments, management);
            } catch (Exception failure) {
                if (postgres != null) postgres.close();
                throw failure;
            }
        }

        Flyway flyway(String target) {
            String[] locations = {"db/migration/", "db/migration/bpm/", "db/migration/notify/", "db/migration/form/", "db/migration/storage/", "db/migration/job/", "db/migration/agent/", "db/migration/iot/", "db/migration/openapi/", "db/migration/system/"};
            var configuration = Flyway.configure().dataSource(dataSource).locations(java.util.Arrays.stream(locations).map(path -> "classpath:" + path + vendor).toArray(String[]::new));
            if (target != null) configuration.target(target);
            return configuration.load();
        }

        long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
        public void close() throws Exception { if (postgres != null) postgres.close(); }
    }
}
