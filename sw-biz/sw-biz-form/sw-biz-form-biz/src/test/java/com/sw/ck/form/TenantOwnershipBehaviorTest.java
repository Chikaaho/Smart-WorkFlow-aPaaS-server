package com.sw.ck.form;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.config.FormAutoConfiguration;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I5 租户归属行为证据（方向 §4.A1/A2）：
 * 默认租户 0 与非零租户 100 分别创建同键 formKey 草稿，双方零串读；
 * 同租户重复 formKey 被拒绝；持久化租户与操作者真实租户一致。
 * 真实 H2 + 真实租户拦截器（与 MybatisPlusConfig 同构装配）。
 */
@SpringBootTest(classes = TenantOwnershipBehaviorTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("I5 表单定义租户归属行为证据")
class TenantOwnershipBehaviorTest {

    private static final long TENANT_X = 100L;

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private FormDefMapper formDefMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_def");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_def (
                    id                   varchar(36)  not null primary key,
                    form_key             varchar(100) not null,
                    name                 varchar(200) not null,
                    logical_table_name   varchar(100),
                    physical_table_name  varchar(100),
                    status               varchar(20)  not null default 'DRAFT',
                    form_version         integer      not null default 1,
                    description          varchar(500),
                    visibility_scope     clob,
                    sub_table_mapping    clob,
                    create_time          timestamp    not null default current_timestamp,
                    create_by            bigint,
                    update_time          timestamp    not null default current_timestamp,
                    update_by            bigint,
                    deleted              smallint     not null default 0,
                    tenant_id            bigint       not null default 0,
                    version              bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_i5_form_key "
                + "ON sw_form_def (tenant_id, form_key, deleted)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_config");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_config (
                    id                   varchar(36)  not null primary key,
                    form_id              varchar(36)  not null,
                    definition           clob,
                    table_name           varchar(200),
                    parent_table         varchar(200),
                    create_time          timestamp    not null default current_timestamp,
                    create_by            bigint,
                    update_time          timestamp    not null default current_timestamp,
                    update_by            bigint,
                    deleted              smallint     not null default 0,
                    tenant_id            bigint       not null default 0,
                    version              bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("DROP TABLE IF EXISTS sw_form_snapshot");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_snapshot (
                    id                   varchar(36)  not null primary key,
                    form_id              varchar(36)  not null,
                    form_version         integer      not null,
                    definition           clob,
                    create_time          timestamp    not null default current_timestamp,
                    create_by            bigint,
                    update_time          timestamp    not null default current_timestamp,
                    update_by            bigint,
                    deleted              smallint     not null default 0,
                    tenant_id            bigint       not null default 0,
                    version              bigint       not null default 0
                )
                """);
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void loginAs(Long tenantId) {
        LoginUser user = new LoginUser();
        user.setUserId(2L);
        user.setTenantId(tenantId);
        LoginUserHolder.set(user);
    }

    @Test
    @DisplayName("A1：默认租户 0 与非零租户 100 各自创建同键 formKey → 双方零串读，归属租户与操作者一致")
    void sameFormKeyAcrossTenants_shouldBeIsolated() {
        String formKey = "i5_isolation_" + UUID.randomUUID().toString().substring(0, 8);

        loginAs(0L);
        var defZero = formDefService.createDraft(formKey, "默认租户表单", null, null);

        loginAs(TENANT_X);
        var defX = formDefService.createDraft(formKey, "租户X表单", null, null);

        // 持久化租户与操作者真实租户一致（裸 SQL 读，绕过拦截器验证物理行）
        var rows = jdbcTemplate.queryForList(
                "select id, tenant_id, create_by from sw_form_def where form_key = ?", formKey);
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            long tenant = ((Number) row.get("tenant_id")).longValue();
            long createBy = ((Number) row.get("create_by")).longValue();
            assertThat(tenant).as("归属租户应为操作者真实租户").isIn(0L, TENANT_X);
            assertThat(createBy).isEqualTo(2L);
        }
        assertThat(rows.stream().map(r -> ((Number) r.get("tenant_id")).longValue()))
                .containsExactlyInAnyOrder(0L, TENANT_X);

        // 各自读回：租户拦截器按当前登录租户过滤，双方零串读
        loginAs(0L);
        assertThat(formDefService.getFormDef(defZero.getId())).isNotNull();
        assertThat(formDefService.getFormDef(defX.getId()))
                .as("租户 0 不得读回租户 X 的定义（tenant_id 过滤）").isNull();

        loginAs(TENANT_X);
        assertThat(formDefService.getFormDef(defX.getId())).isNotNull();
        assertThat(formDefService.getFormDef(defZero.getId()))
                .as("租户 X 不得读回租户 0 的定义（tenant_id 过滤）").isNull();

        // 同键 getFormDefByKey 各自命中本租户定义
        loginAs(0L);
        assertThat(formDefService.getFormDefByKey(formKey)).isNotNull();
        loginAs(TENANT_X);
        assertThat(formDefService.getFormDefByKey(formKey)).isNotNull();
    }

    @Test
    @DisplayName("A2：同租户重复 formKey → 被拒绝（FORM_KEY_DUPLICATE）")
    void duplicateFormKeySameTenant_shouldReject() {
        String formKey = "i5_dup_" + UUID.randomUUID().toString().substring(0, 8);
        loginAs(TENANT_X);
        formDefService.createDraft(formKey, "第一个", null, null);
        assertThatThrownBy(() -> formDefService.createDraft(formKey, "第二个", null, null))
                .as("同租户重复 formKey 必须拒绝")
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);
    }

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    @EnableTransactionManagement
    static class Config {

        @Bean
        public DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:h2:mem:i5_tenant_owner;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            ds.setUsername("sa");
            ds.setPassword("");
            return ds;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.form.entity");

            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(ibatisConfig);

            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);

            LoginContextProvider loginContextProvider = new LoginContextProvider() {
                @Override public Long getUserId() {
                    LoginUser u = LoginUserHolder.get();
                    return u != null ? u.getUserId() : null;
                }
                @Override public Long getTenantId() {
                    LoginUser u = LoginUserHolder.get();
                    return u != null ? u.getTenantId() : null;
                }
                @Override public Long getDeptId() { return null; }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                @Override public boolean isSuperAdmin() { return false; }
            };
            CommonMetaObjectHandler metaObjectHandler = new CommonMetaObjectHandler(loginContextProvider);
            metaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f && f.getId() == null) {
                    f.setId(new com.sw.ck.form.entity.FormIdGenerator().generate());
                }
            });
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);

            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(new TenantProperties(), loginContextProvider)));
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            factory.setPlugins(interceptor);

            return factory.getObject();
        }

        @Bean
        public TenantProperties tenantProperties() {
            TenantProperties props = new TenantProperties();
            props.setEnabled(true);
            props.setIgnoreTables(java.util.List.of("sys_menu"));
            return props;
        }

        @Bean
        public FormDefService formDefService(FormDefMapper formDefMapper,
                                             com.sw.ck.form.mapper.FormConfigMapper formConfigMapper,
                                             com.sw.ck.form.mapper.FormSnapshotMapper formSnapshotMapper) {
            // 本测试只覆盖元数据路径（草稿/读回），DynamicTableManager 不参与
            return new FormDefServiceImpl(formDefMapper, formConfigMapper, formSnapshotMapper, null,
                    new com.sw.ck.form.entity.FormIdGenerator(),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    new com.sw.ck.form.service.FormVisibilityRules(
                            new com.fasterxml.jackson.databind.ObjectMapper()),
                    new com.sw.ck.form.service.FormulaEngine(),
                    new com.sw.ck.form.service.FieldPermissionService(
                            new com.fasterxml.jackson.databind.ObjectMapper()),
                    null, null, null);
        }
    }
}
