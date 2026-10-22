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
                var factory = new MybatisSqlSessionFactoryBean();
                factory.setDataSource(dataSource); factory.setConfiguration(config); factory.setGlobalConfig(global); factory.setPlugins(interceptor);
                var sessions = factory.getObject();
                for (Class<?> mapper : new Class<?>[]{SysTenantMapper.class, SysDeptMapper.class, SysUserMapper.class, SysRoleMapper.class, SysRoleMenuMapper.class, SysUserRoleMapper.class}) {
                    sessions.getConfiguration().addMapper(mapper);
                }
                var template = new SqlSessionTemplate(sessions);
                var transactions = new DataSourceTransactionManager(dataSource);
                var target = new TenantAdminService(template.getMapper(SysTenantMapper.class), template.getMapper(SysDeptMapper.class),
                        template.getMapper(SysUserMapper.class), template.getMapper(SysRoleMapper.class), template.getMapper(SysRoleMenuMapper.class),
                        template.getMapper(SysUserRoleMapper.class), encoder, transactions);
                var proxy = new ProxyFactory(target);
                proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
                service = (TenantAdminService) proxy.getProxy();
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
