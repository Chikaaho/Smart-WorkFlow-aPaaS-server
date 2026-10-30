package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.dto.ExtQueryResult;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.ExtDatasourceGrantPort;
import com.sw.ck.form.api.port.ExtDatasourceQueryPort;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormExtQueryMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
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

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE020-G1 准入语义行为证据（H2 集成）：
 * 设计预览查当前授权（撤权即拒）；已发布运行按绑定+字段资格放行、不追溯原设计者授权；
 * 知道 queryKey 但无绑定/字段资格者拒绝。授权判定经真实 sw_bpm_ext_datasource_grant
 * 表持久读取（与生产迁移同 DDL），不用始终放行的桩；执行引擎行为由 engine 受控链测试承接。
 */
@SpringBootTest(classes = FormExtQueryAdmissionTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("BE020-G1：设计预览授权与已发布运行绑定/字段资格（H2）")
class FormExtQueryAdmissionTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final Long DESIGNER = 31L;
    private static final Long VIEWER = 32L;
    private static final Long ROLE_VIEWER = 33L;
    private static final Long OTHER_TENANT_USER = 21L;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FormExtDataService extDataService;

    private static final String DEFINITION = """
            {"fields":[
              {"name":"title","type":"TEXT","label":"标题"},
              {"name":"ds_field","type":"DATASOURCE","label":"授权对象",
               "dsBinding":{"queryKey":"g2adm_auth","version":1,"valueField":"value","displayField":"display"}},
              {"name":"ds_other","type":"DATASOURCE","label":"受限对象",
               "dsBinding":{"queryKey":"g2adm_other","version":1,"valueField":"value","displayField":"display"}}],
             "fieldPermissions":{"ds_other":{"view":["role:viewer"]}}}
            """;

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url("jdbc:h2:mem:f020admission;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public LoginContextProvider loginContextProvider() {
            return new LoginContextProvider() {
                @Override public Long getUserId() {
                    LoginUser u = LoginUserHolder.get();
                    return u == null ? null : u.getUserId();
                }
                @Override public Long getTenantId() {
                    LoginUser u = LoginUserHolder.get();
                    return u == null ? null : u.getTenantId();
                }
                @Override public Long getDeptId() { return null; }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                @Override public boolean isSuperAdmin() { return false; }
            };
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, LoginContextProvider loginContextProvider) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.form.entity");
            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            ibatisConfig.setUseGeneratedKeys(true);
            factory.setConfiguration(ibatisConfig);
            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);
            globalConfig.setMetaObjectHandler(new CommonMetaObjectHandler(loginContextProvider));
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
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public FieldPermissionService fieldPermissionService(ObjectMapper objectMapper) {
            return new FieldPermissionService(objectMapper);
        }

        /** 执行桩：固定一行契约内数据（执行引擎真实行为由 engine 受控链测试承接）。 */
        @Bean
        public ExtDatasourceQueryPort extDatasourceQueryPort() {
            return (datasourceId, sql, operatorId, operatorName) -> Optional.of(
                    ExtQueryResult.builder()
                            .columns(List.of("value", "display"))
                            .rows(List.of(Map.of("value", "V-1", "display", "对象一")))
                            .rowCount(1)
                            .build());
        }

        /**
         * 授权端口（真实持久读取）：与生产适配同决策语义——管理员旁路需数据源存在，
         * 其余主体按 USER ID / ROLE 编码匹配授权行；授权行经真实迁移 DDL 表读写。
         */
        @Bean
        public ExtDatasourceGrantPort extDatasourceGrantPort(JdbcTemplate jdbcTemplate) {
            return new ExtDatasourceGrantPort() {
                private boolean isManager(LoginUser user) {
                    if (user == null) {
                        return false;
                    }
                    if (user.isSuperAdmin()) {
                        return true;
                    }
                    return user.getPermissions() != null
                            && user.getPermissions().contains("workflow:datasource:manage");
                }

                private boolean datasourceExists(Long tenantId, Long datasourceId) {
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM sw_bpm_ext_datasource "
                                    + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                            Integer.class, datasourceId, tenantId);
                    return count != null && count > 0;
                }

                @Override
                public java.util.Optional<Boolean> isReferenceAllowed(Long datasourceId) {
                    return java.util.Optional.of(isReferenceAllowedInternal(datasourceId));
                }

                private boolean isReferenceAllowedInternal(Long datasourceId) {
                    LoginUser user = LoginUserHolder.get();
                    if (user == null || datasourceId == null) {
                        return false;
                    }
                    if (!datasourceExists(user.getTenantId(), datasourceId)) {
                        return false;
                    }
                    if (isManager(user)) {
                        return true;
                    }
                    Integer userMatched = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM sw_bpm_ext_datasource_grant "
                                    + "WHERE tenant_id = ? AND datasource_id = ? AND deleted = 0 "
                                    + "AND subject_type = 'USER' AND subject_key = ?",
                            Integer.class, user.getTenantId(), datasourceId,
                            String.valueOf(user.getUserId()));
                    if (userMatched != null && userMatched > 0) {
                        return true;
                    }
                    if (user.getRoles() != null && !user.getRoles().isEmpty()) {
                        String in = user.getRoles().stream()
                                .map(role -> "'" + role.replace("'", "''") + "'")
                                .collect(java.util.stream.Collectors.joining(","));
                        Integer roleMatched = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM sw_bpm_ext_datasource_grant "
                                        + "WHERE tenant_id = ? AND datasource_id = ? AND deleted = 0 "
                                        + "AND subject_type = 'ROLE' AND subject_key IN (" + in + ")",
                                Integer.class, user.getTenantId(), datasourceId);
                        return roleMatched != null && roleMatched > 0;
                    }
                    return false;
                }

                @Override
                public java.util.Optional<Set<Long>> referenceAllowedDatasourceIds() {
                    return java.util.Optional.of(referenceAllowedDatasourceIdsInternal());
                }

                private Set<Long> referenceAllowedDatasourceIdsInternal() {
                    LoginUser user = LoginUserHolder.get();
                    if (user == null) {
                        return Set.of();
                    }
                    Set<Long> ids = new LinkedHashSet<>();
                    if (isManager(user)) {
                        jdbcTemplate.queryForList(
                                "SELECT id FROM sw_bpm_ext_datasource "
                                        + "WHERE tenant_id = ? AND deleted = 0",
                                Long.class, user.getTenantId()).forEach(ids::add);
                        return ids;
                    }
                    // 简化映射：仅按 USER 匹配（本测试清单过滤用例使用 USER 主体）
                    ids.addAll(jdbcTemplate.queryForList(
                            "SELECT datasource_id FROM sw_bpm_ext_datasource_grant "
                                    + "WHERE tenant_id = ? AND deleted = 0 AND subject_type = 'USER' "
                                    + "AND subject_key = ?",
                            Long.class, user.getTenantId(), String.valueOf(user.getUserId())));
                    return ids;
                }
            };
        }

        @Bean
        public FormExtDataService formExtDataService(
                FormExtQueryMapper extQueryMapper,
                org.springframework.beans.factory.ObjectProvider<ExtDatasourceQueryPort> queryPort,
                org.springframework.beans.factory.ObjectProvider<ExtDatasourceGrantPort> grantPort,
                FormDefMapper formDefMapper,
                FormConfigMapper formConfigMapper,
                FieldPermissionService fieldPermissionService,
                ObjectMapper objectMapper) {
            return new FormExtDataService(extQueryMapper, queryPort, grantPort, formDefMapper,
                    formConfigMapper, fieldPermissionService, objectMapper);
        }
    }

    @BeforeEach
    void setUp() {
        createTables();
        insertFixtures();
        grant(DESIGNER);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        jdbcTemplate.execute("DELETE FROM sw_bpm_ext_datasource_grant");
        jdbcTemplate.execute("DELETE FROM sw_bpm_ext_datasource");
        jdbcTemplate.execute("DELETE FROM sw_form_ext_query");
        jdbcTemplate.execute("DELETE FROM sw_form_config");
        jdbcTemplate.execute("DELETE FROM sw_form_def");
    }

    private void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_ext_query (
                    id VARCHAR(36) PRIMARY KEY, datasource_id BIGINT NOT NULL,
                    query_key VARCHAR(64) NOT NULL, query_version INT NOT NULL,
                    sql_text CLOB NOT NULL, output_fields CLOB NOT NULL,
                    enabled INT NOT NULL DEFAULT 1,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_def (
                    id VARCHAR(36) PRIMARY KEY, form_key VARCHAR(100) NOT NULL,
                    form_type VARCHAR(20) NOT NULL DEFAULT 'BUSINESS',
                    name VARCHAR(200) NOT NULL, logical_table_name VARCHAR(100),
                    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', physical_table_name VARCHAR(100),
                    form_version INT NOT NULL DEFAULT 1, description VARCHAR(500),
                    visibility_scope TEXT, sub_table_mapping TEXT,
                    app_id VARCHAR(36), dir_id VARCHAR(36),
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_config (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL, table_name VARCHAR(200),
                    parent_table VARCHAR(200), definition CLOB NOT NULL,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_bpm_ext_datasource (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by BIGINT,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0,
                    name VARCHAR(100), type VARCHAR(32), jdbc_url VARCHAR(500),
                    driver_class VARCHAR(128), username VARCHAR(100),
                    password_cipher VARCHAR(500), read_only SMALLINT DEFAULT 1,
                    enabled SMALLINT DEFAULT 1)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_bpm_ext_datasource_grant (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by BIGINT,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0,
                    datasource_id BIGINT NOT NULL,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_key VARCHAR(64) NOT NULL)
                """);
    }

    private void insertFixtures() {
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_ext_datasource (id, tenant_id, name, type, enabled, deleted, version)
                VALUES (9, ?, '报表库', 'h2', 1, 0, 0)
                """, TENANT_1);
        jdbcTemplate.update("""
                INSERT INTO sw_form_ext_query (id, datasource_id, query_key, query_version, sql_text,
                    output_fields, enabled, tenant_id, deleted, version)
                VALUES ('adm-q-1', 9, 'g2adm_auth', 1, 'SELECT 1', '[{"name":"value"},{"name":"display"}]', 1, ?, 0, 0)
                """, TENANT_1);
        jdbcTemplate.update("""
                INSERT INTO sw_form_ext_query (id, datasource_id, query_key, query_version, sql_text,
                    output_fields, enabled, tenant_id, deleted, version)
                VALUES ('adm-q-2', 9, 'g2adm_other', 1, 'SELECT 1', '[{"name":"value"},{"name":"display"}]', 1, ?, 0, 0)
                """, TENANT_1);
        jdbcTemplate.update("""
                INSERT INTO sw_form_def (id, form_key, name, status, form_version, tenant_id, deleted, version)
                VALUES ('adm-form-1', 'g2adm_form', '准入测试表单', 'PUBLISHED', 1, ?, 0, 0)
                """, TENANT_1);
        jdbcTemplate.update("""
                INSERT INTO sw_form_config (id, form_id, definition, tenant_id, deleted, version)
                VALUES ('adm-cfg-1', 'adm-form-1', ?, ?, 0, 0)
                """, DEFINITION, TENANT_1);
    }

    private void grant(Long userId) {
        jdbcTemplate.update("""
                INSERT INTO sw_bpm_ext_datasource_grant (id, tenant_id, datasource_id,
                    subject_type, subject_key, deleted, version)
                VALUES (?, ?, 9, 'USER', ?, 0, 0)
                """, 100L + userId, TENANT_1, String.valueOf(userId));
    }

    private void revoke(Long userId) {
        jdbcTemplate.update("UPDATE sw_bpm_ext_datasource_grant SET deleted = 1 "
                        + "WHERE tenant_id = ? AND subject_type = 'USER' AND subject_key = ?",
                TENANT_1, String.valueOf(userId));
    }

    private void login(Long userId, Long tenantId, List<String> roles, List<String> permissions) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        user.setRoles(roles);
        user.setPermissions(permissions);
        LoginUserHolder.set(user);
    }

    private int grantRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_ext_datasource_grant WHERE deleted = 0", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("有权设计者可预览：授权行命中即放行")
    void grantedDesignerCanPreview() {
        login(DESIGNER, TENANT_1, List.of(), List.of("form:design:save"));
        ExtQueryResult result = extDataService.preview("g2adm_auth", null);
        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("撤权后直接构造同 queryKey 预览拒绝，且授权/契约/数据源状态零变更")
    void revokedDesignerDirectPreviewRejectedWithZeroChange() {
        login(DESIGNER, TENANT_1, List.of(), List.of("form:design:save"));
        assertThat(extDataService.preview("g2adm_auth", null).getRowCount()).isEqualTo(1);

        revoke(DESIGNER);
        assertThatThrownBy(() -> extDataService.preview("g2adm_auth", null))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED.getCode()));

        // 零变更：授权行（软删 1 行）、契约启停、数据源状态均不变
        Integer grants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_ext_datasource_grant "
                        + "WHERE tenant_id = ? AND subject_key = ? AND deleted = 1",
                Integer.class, TENANT_1, String.valueOf(DESIGNER));
        assertThat(grants).isEqualTo(1);
        Integer contractEnabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM sw_form_ext_query WHERE query_key = 'g2adm_auth'", Integer.class);
        assertThat(contractEnabled).isEqualTo(1);
        Integer dsEnabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM sw_bpm_ext_datasource WHERE id = 9", Integer.class);
        assertThat(dsEnabled).isEqualTo(1);
    }

    @Test
    @DisplayName("运行读取：有发布绑定且字段可查看即放行，不要求设计授权")
    void runtimeReadWithPublishedBindingAllowed() {
        login(VIEWER, TENANT_1, List.of(), List.of("form:data:query"));
        ExtQueryResult result = extDataService.previewForPublishedBinding(
                "g2adm_auth", null, "g2adm_form", "ds_field");
        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("运行读取：仅知道 queryKey（无表单绑定上下文）拒绝")
    void runtimeReadKnowingOnlyQueryKeyRejected() {
        login(VIEWER, TENANT_1, List.of(), List.of("form:data:query"));
        assertThatThrownBy(() -> extDataService.preview("g2adm_auth", null))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("运行读取：字段绑定其他契约（伪造绑定）拒绝")
    void runtimeReadWithMismatchedBindingRejected() {
        login(VIEWER, TENANT_1, List.of(), List.of("form:data:query"));
        assertThatThrownBy(() -> extDataService.previewForPublishedBinding(
                "g2adm_auth", null, "g2adm_form", "ds_other"))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("运行读取：字段查看权不足拒绝；具备角色资格者放行")
    void runtimeReadRespectsFieldViewPermission() {
        login(VIEWER, TENANT_1, List.of(), List.of("form:data:query"));
        assertThatThrownBy(() -> extDataService.previewForPublishedBinding(
                "g2adm_other", null, "g2adm_form", "ds_other"))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED.getCode()));

        login(ROLE_VIEWER, TENANT_1, List.of("viewer"), List.of("form:data:query"));
        assertThat(extDataService.previewForPublishedBinding(
                "g2adm_other", null, "g2adm_form", "ds_other").getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("运行读取：表单不存在/未发布拒绝")
    void runtimeReadOnUnpublishedFormRejected() {
        login(VIEWER, TENANT_1, List.of(), List.of("form:data:query"));
        assertThatThrownBy(() -> extDataService.previewForPublishedBinding(
                "g2adm_auth", null, "g2adm_missing", "ds_field"))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("直接构造契约启停请求：跨租户 queryKey 拒绝且启停状态零变更")
    void crossTenantContractToggleRejectedWithZeroChange() {
        login(OTHER_TENANT_USER, TENANT_2, List.of(), List.of("form:design:save"));
        assertThatThrownBy(() -> extDataService.setContractEnabled("g2adm_auth", false))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.EXT_QUERY_NOT_FOUND.getCode()));
        Integer enabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM sw_form_ext_query WHERE query_key = 'g2adm_auth'", Integer.class);
        assertThat(enabled).isEqualTo(1);
    }

    @Test
    @DisplayName("跨租户：其他租户主体读取本租户绑定按表单不存在拒绝，授权行零变更")
    void crossTenantReadRejectedWithZeroChange() {
        login(OTHER_TENANT_USER, TENANT_2, List.of(), List.of("form:data:query"));
        assertThatThrownBy(() -> extDataService.previewForPublishedBinding(
                "g2adm_auth", null, "g2adm_form", "ds_field"))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.EXT_DATASOURCE_UNAUTHORIZED.getCode()));
        assertThat(grantRowCount()).isEqualTo(1);
    }
}
