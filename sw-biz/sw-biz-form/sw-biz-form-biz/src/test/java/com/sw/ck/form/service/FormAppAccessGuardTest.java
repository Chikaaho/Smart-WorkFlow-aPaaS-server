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
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.dto.FormDataUpdateRequest;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.FormRecordAccessPort;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.mapper.FormTraceMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 应用范围访问闸门（{@link FormAppAccessGuard}）行为证据（H2 PostgreSQL 模式 + 真实租户拦截器）。
 *
 * <p>覆盖补充提示01 G2 的最小必要断言：</p>
 * <ol>
 *   <li>未绑定应用的表单不受闸门约束（既有表单路径不回归）；</li>
 *   <li>指定范围外用户：列表/提交/发起被拒，且拒绝不新增记录与实例；</li>
 *   <li>指定空集合 = 无人可见（连创建者也不可见）；</li>
 *   <li>停用：新列表/新提交被拒，本人提交记录的详情保留；</li>
 *   <li>范围收窄：原办理人（流程参与关系）详情保留，新发起被拒，非参与人详情按不存在拒绝；</li>
 *   <li>跨租户应用对象不可访问（租户列隔离 + 可见范围）；</li>
 *   <li>更新路径与详情同口径。</li>
 * </ol>
 */
@SpringBootTest(classes = FormAppAccessGuardTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("应用范围闸门·G2 行为证据（H2）")
class FormAppAccessGuardTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    /** 应用范围内用户。 */
    private static final Long USER_IN_SCOPE = 11L;
    /** 范围外用户。 */
    private static final Long USER_OUT_OF_SCOPE = 12L;
    /** 范围收窄后仍在范围内的用户。 */
    private static final Long USER_AFTER_NARROW = 13L;
    private static final Long USER_OTHER_TENANT = 21L;

    /** 流程参与关系桩：设为 true 时 {@link FormRecordAccessPort} 判定为参与人。 */
    static final AtomicBoolean PARTICIPANT_STUB = new AtomicBoolean(false);

    @Configuration
    @org.mybatis.spring.annotation.MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:appguard;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public FormAppScopeMatcher formAppScopeMatcher(JdbcTemplate jdbcTemplate) {
            return new FormAppScopeMatcher(jdbcTemplate);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
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
                DataSource dataSource,
                LoginContextProvider loginContextProvider) throws Exception {
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
            CommonMetaObjectHandler metaObjectHandler = new CommonMetaObjectHandler(loginContextProvider);
            metaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f && f.getId() == null) {
                    f.setId(new FormIdGenerator().generate());
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
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public DynamicTableManager dynamicTableManager(JdbcTemplate jdbcTemplate) {
            return new DynamicTableManager(jdbcTemplate);
        }

        @Bean
        public FormulaEngine formulaEngine() {
            return new FormulaEngine();
        }

        @Bean
        public FieldPermissionService fieldPermissionService(ObjectMapper objectMapper) {
            return new FieldPermissionService(objectMapper);
        }

        @Bean
        public FormFieldValidator formFieldValidator(FormConfigMapper formConfigMapper,
                                                     ObjectMapper objectMapper) {
            return new FormFieldValidator(formConfigMapper, objectMapper);
        }

        @Bean
        public FormDefService formDefService(FormDefMapper formDefMapper,
                                             FormConfigMapper formConfigMapper,
                                             FormSnapshotMapper formSnapshotMapper,
                                             DynamicTableManager dynamicTableManager,
                                             ObjectMapper objectMapper) {
            return new com.sw.ck.form.service.impl.FormDefServiceImpl(formDefMapper, formConfigMapper,
                    formSnapshotMapper, dynamicTableManager, new FormIdGenerator(), objectMapper);
        }

        /** 流程参与关系桩：判定结果由 {@link #PARTICIPANT_STUB} 驱动。 */
        @Bean
        public FormRecordAccessPort formRecordAccessPort() {
            return (tenantId, userId, formKey, recordId) -> Optional.of(PARTICIPANT_STUB.get());
        }

        @Bean
        public FormAppAccessGuard formAppAccessGuard(com.sw.ck.form.mapper.FormAppMapper appMapper,
                                                     com.sw.ck.form.mapper.FormAppScopeMapper appScopeMapper,
                                                     FormAppScopeMatcher scopeMatcher,
                                                     JdbcTemplate jdbcTemplate,
                                                     FormRecordAccessPort formRecordAccessPort) {
            FormAppAccessGuard guard = new FormAppAccessGuard(appMapper, appScopeMapper, scopeMatcher,
                    jdbcTemplate);
            guard.setFormRecordAccessPort(formRecordAccessPort);
            return guard;
        }

        @Bean
        public FormDataQueryService formDataQueryService(FormDefService formDefService,
                                                         FormDefMapper formDefMapper,
                                                         FormConfigMapper formConfigMapper,
                                                         JdbcTemplate jdbcTemplate,
                                                         ObjectMapper objectMapper,
                                                         FormAppAccessGuard guard) {
            FormDataQueryService service = new FormDataQueryService(formDefService, formDefMapper,
                    formConfigMapper, jdbcTemplate, objectMapper);
            service.setAppAccessGuard(guard);
            return service;
        }

        @Bean
        public FormDataUpdateService formDataUpdateService(FormDefService formDefService,
                                                           FormDefMapper formDefMapper,
                                                           FormConfigMapper formConfigMapper,
                                                           JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper,
                                                           FormFieldValidator formFieldValidator,
                                                           FormAppAccessGuard guard) {
            FormDataUpdateService service = new FormDataUpdateService(formDefService, formDefMapper,
                    formConfigMapper, jdbcTemplate, objectMapper, new FormIdGenerator(),
                    mock(DictFacade.class), formFieldValidator);
            service.setAppAccessGuard(guard);
            return service;
        }

        @Bean
        public FormFieldEnrichmentService formFieldEnrichmentService(
                FormConfigMapper formConfigMapper,
                ObjectMapper objectMapper,
                FormulaEngine formulaEngine,
                FieldPermissionService fieldPermissionService,
                FormDefMapper formDefMapper,
                JdbcTemplate jdbcTemplate) {
            return new FormFieldEnrichmentService(formConfigMapper, objectMapper, formulaEngine,
                    fieldPermissionService,
                    emptyProvider(), emptyProvider(), emptyProvider(),
                    formDefMapper, jdbcTemplate, emptyProvider());
        }

        @Bean
        public FormSubmitService formSubmitService(FormDefMapper formDefMapper,
                                                   FormTraceMapper formTraceMapper,
                                                   DynamicTableManager dynamicTableManager,
                                                   ObjectMapper objectMapper,
                                                   JdbcTemplate jdbcTemplate,
                                                   FormFieldValidator formFieldValidator,
                                                   FormFieldEnrichmentService enrichmentService,
                                                   FormAppAccessGuard guard) {
            FormSubmitService service = new FormSubmitService(formDefMapper, formTraceMapper,
                    dynamicTableManager, new FormIdGenerator(), objectMapper, jdbcTemplate,
                    mock(DictFacade.class), mock(DomainEventPublisher.class), Optional.empty(),
                    formFieldValidator, emptyProvider(), enrichmentService);
            service.setAppAccessGuard(guard);
            return service;
        }

        @SuppressWarnings("unchecked")
        private static <T> ObjectProvider<T> emptyProvider() {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            org.mockito.Mockito.lenient().when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }
    }

    @Autowired
    private FormDataQueryService queryService;
    @Autowired
    private FormDataUpdateService updateService;
    @Autowired
    private FormSubmitService submitService;
    @Autowired
    private FormDefService formDefService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private ApplicationContext ctx;

    private final List<String> createdTables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PARTICIPANT_STUB.set(false);
        LoginUserHolder.clear();
        createMetadataTables();
        loginAs(TENANT_1, USER_IN_SCOPE);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        PARTICIPANT_STUB.set(false);
        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + table + "\" CASCADE");
            } catch (Exception ignored) {
                // 清理容错
            }
        }
        createdTables.clear();
    }

    private void createMetadataTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_app (
                    id                  varchar(36)  not null primary key,
                    tenant_id           bigint       not null default 0,
                    name                varchar(100) not null,
                    icon                varchar(64),
                    color               varchar(32),
                    sort_no             integer      not null default 0,
                    visible_scope_type  varchar(20)  not null default 'SPECIFIED',
                    status              varchar(20)  not null default 'ENABLED',
                    source              varchar(20)  not null default 'MANUAL',
                    legacy_category_id  bigint,
                    deleted             smallint     not null default 0,
                    create_time         timestamp    not null default current_timestamp,
                    create_by           bigint,
                    update_time         timestamp    not null default current_timestamp,
                    update_by           bigint,
                    version             bigint       not null default 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_app_scope (
                    id          varchar(36) not null primary key,
                    tenant_id   bigint      not null default 0,
                    app_id      varchar(36) not null,
                    scope_type  varchar(20) not null,
                    scope_id    varchar(64) not null,
                    deleted     smallint    not null default 0,
                    create_time timestamp   not null default current_timestamp,
                    create_by   bigint,
                    update_time timestamp   not null default current_timestamp,
                    update_by   bigint,
                    version     bigint      not null default 0
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_sw_form_app_scope_app "
                + "ON sw_form_app_scope (tenant_id, app_id, deleted)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_def (
                    id VARCHAR(36) PRIMARY KEY, form_key VARCHAR(100) NOT NULL,
                    form_type           varchar(20)  not null default 'BUSINESS',
                    name VARCHAR(200) NOT NULL, logical_table_name VARCHAR(100),
                    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', physical_table_name VARCHAR(100),
                    form_version INT NOT NULL DEFAULT 1, description VARCHAR(500),
                    visibility_scope TEXT, sub_table_mapping TEXT,
                    app_id               varchar(36),
                    dir_id               varchar(36),
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
                CREATE TABLE IF NOT EXISTS sw_form_snapshot (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL, form_version INT NOT NULL,
                    definition CLOB NOT NULL, tenant_id BIGINT NOT NULL DEFAULT 0,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_trace (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL, record_id VARCHAR(36) NOT NULL,
                    submit_user_id BIGINT NOT NULL, submit_ip VARCHAR(200),
                    submit_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    device_fingerprint VARCHAR(200), user_agent VARCHAR(500),
                    submit_idempotency_key VARCHAR(128), tenant_id BIGINT NOT NULL DEFAULT 0,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
    }

    private void loginAs(Long tenantId, Long userId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        LoginUserHolder.set(user);
    }

    // ==================== 夹具 ====================

    private void insertApp(String id, long tenantId, String scope, String status) {
        jdbcTemplate.update("INSERT INTO sw_form_app (id, tenant_id, name, sort_no, visible_scope_type, "
                        + "status, source, deleted, version) VALUES (?, ?, ?, 0, ?, ?, 'MANUAL', 0, 0)",
                id, tenantId, "应用-" + id, scope, status);
    }

    private void insertAppUser(String id, long tenantId, String appId, long userId) {
        jdbcTemplate.update("INSERT INTO sw_form_app_scope "
                + "(id, tenant_id, app_id, scope_type, scope_id, deleted, version) "
                + "VALUES (?, ?, ?, 'USER', ?, 0, 0)", id, tenantId, appId, String.valueOf(userId));
    }

    /** 建已发布表单并把归属列写成指定应用（归属写入走 SQL，聚焦闸门本身）。 */
    private FormDefEntity createPublishedFormBoundTo(String formKey, String appId) {
        loginAs(TENANT_1, USER_IN_SCOPE);
        var draft = formDefService.createDraft(formKey, "G2-" + formKey, null, null);
        formDefService.saveConfig(draft.getId(),
                "{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\",\"label\":\"标题\"}]}");
        formDefService.publish(draft.getId());
        FormDefEntity entity = ultimateFormDefMapper().selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());
        if (appId != null) {
            jdbcTemplate.update("UPDATE sw_form_def SET app_id = ? WHERE id = ?", appId, draft.getId());
        }
        return entity;
    }

    private FormDefMapper ultimateFormDefMapper() {
        return AopTestUtils.getUltimateTargetObject(formDefMapper);
    }

    private FormDataQueryRequest page(int pageNum, int pageSize) {
        FormDataQueryRequest req = new FormDataQueryRequest();
        req.setPageNum(pageNum);
        req.setPageSize(pageSize);
        return req;
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private long physicalRowCount(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + table + "\" WHERE \"deleted\" = 0", Long.class);
        return count == null ? 0L : count;
    }

    private int catchCode(Runnable action) {
        try {
            action.run();
            return -1;
        } catch (BaseException e) {
            return e.getCode();
        }
    }

    // ==================== 1. 未绑定应用不回归 ====================

    @Test
    @DisplayName("未绑定应用的表单：列表/提交/详情不受应用闸门约束")
    void unboundFormUnaffected() {
        FormDefEntity form = createPublishedFormBoundTo("g2_unbound", null);
        String recordId = submitService.submitForm("g2_unbound", data("title", "无应用记录"), null, null, null);

        assertThat(queryService.queryFormData("g2_unbound", page(1, 10)).getTotal()).isEqualTo(1L);
        assertThat(queryService.getRecordDetail("g2_unbound", recordId)).containsKey("title");
        assertThat(physicalRowCount(form.getPhysicalTableName())).isEqualTo(1L);
    }

    // ==================== 2. 范围外：新访问/新发起受限且无副作用 ====================

    @Test
    @DisplayName("范围外用户：列表与提交/发起被拒，拒绝后不新增记录、不新增实例受理")
    void outOfScopeUserRejectedWithoutSideEffects() {
        insertApp("app-specified", TENANT_1, "SPECIFIED", "ENABLED");
        insertAppUser("app-specified-u11", TENANT_1, "app-specified", USER_IN_SCOPE);
        FormDefEntity form = createPublishedFormBoundTo("g2_scope", "app-specified");
        String table = form.getPhysicalTableName();

        // 范围内用户正向可用
        String recordId = submitService.submitForm("g2_scope", data("title", "范围内记录"), null, null, null);
        assertThat(queryService.queryFormData("g2_scope", page(1, 10)).getTotal()).isEqualTo(1L);

        // 范围外用户：列表、详情（非本人记录且非参与人）、提交全部拒绝
        loginAs(TENANT_1, USER_OUT_OF_SCOPE);
        assertThat(catchCode(() -> queryService.queryFormData("g2_scope", page(1, 10))))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());
        assertThat(catchCode(() -> queryService.getRecordDetail("g2_scope", recordId)))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());
        assertThat(catchCode(() -> submitService.submitForm("g2_scope",
                data("title", "越界提交"), null, null, null)))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());

        // 无副作用回读：记录数不变（拒绝不新增记录），范围内视角仍只有 1 条
        loginAs(TENANT_1, USER_IN_SCOPE);
        assertThat(physicalRowCount(table)).isEqualTo(1L);
        assertThat(queryService.queryFormData("g2_scope", page(1, 10)).getTotal()).isEqualTo(1L);
    }

    // ==================== 3. 指定空集合 ====================

    @Test
    @DisplayName("指定集合为空：无人可见——创建者本人也被拒（空集不误作全员）")
    void emptySpecifiedScopeInvisibleToEveryone() {
        insertApp("app-empty", TENANT_1, "SPECIFIED", "ENABLED");
        createPublishedFormBoundTo("g2_empty_scope", "app-empty");

        loginAs(TENANT_1, USER_IN_SCOPE);
        assertThat(catchCode(() -> queryService.queryFormData("g2_empty_scope", page(1, 10))))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());
        assertThat(catchCode(() -> submitService.submitForm("g2_empty_scope",
                data("title", "空集提交"), null, null, null)))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());
    }

    // ==================== 4. 停用：新访问受限、本人历史保留 ====================

    @Test
    @DisplayName("应用停用：新列表/新提交被拒（不新增记录）；本人提交记录详情仍可读")
    void disabledAppBlocksNewAccessKeepsOwnHistory() {
        insertApp("app-disable", TENANT_1, "SPECIFIED", "ENABLED");
        insertAppUser("app-disable-u11", TENANT_1, "app-disable", USER_IN_SCOPE);
        FormDefEntity form = createPublishedFormBoundTo("g2_disable", "app-disable");
        String recordId = submitService.submitForm("g2_disable", data("title", "停用前记录"), null, null, null);

        jdbcTemplate.update("UPDATE sw_form_app SET status = 'DISABLED' WHERE id = 'app-disable'");

        assertThat(catchCode(() -> queryService.queryFormData("g2_disable", page(1, 10))))
                .isEqualTo(FormErrorCode.APP_DISABLED.getCode());
        assertThat(catchCode(() -> submitService.submitForm("g2_disable",
                data("title", "停用后提交"), null, null, null)))
                .isEqualTo(FormErrorCode.APP_DISABLED.getCode());
        // 本人提交记录：合法历史访问保留
        assertThat(queryService.getRecordDetail("g2_disable", recordId)).containsKey("title");
        // 更新：本人记录同样保留（本人提交关系）
        FormDataUpdateRequest update = new FormDataUpdateRequest();
        update.setData(data("title", "停用后本人修订"));
        update.setVersion(0L);
        updateService.updateRecord("g2_disable", recordId, update);
        assertThat(queryService.getRecordDetail("g2_disable", recordId).get("title"))
                .isEqualTo("停用后本人修订");
        // 拒绝不新增记录：物理行数仍为 1
        assertThat(physicalRowCount(form.getPhysicalTableName())).isEqualTo(1L);
    }

    // ==================== 5. 范围收窄：原办理人保留、新发起拒绝 ====================

    @Test
    @DisplayName("范围收窄：原办理人（本人提交/流程参与）详情保留，新列表与新发起拒绝，非参与人按不存在拒绝")
    void narrowedScopeKeepsParticipantHistoryRejectsNewStart() {
        insertApp("app-narrow", TENANT_1, "ALL", "ENABLED");
        FormDefEntity form = createPublishedFormBoundTo("g2_narrow", "app-narrow");
        // 收窄前由范围内用户提交记录（本人提交通道）
        String ownRecordId = submitService.submitForm("g2_narrow",
                data("title", "原范围内记录"), null, null, null);

        // 收窄：切到 SPECIFIED，只保留 USER_AFTER_NARROW（原范围内用户被移出）
        jdbcTemplate.update("UPDATE sw_form_app SET visible_scope_type = 'SPECIFIED' WHERE id = 'app-narrow'");
        insertAppUser("app-narrow-u13", TENANT_1, "app-narrow", USER_AFTER_NARROW);

        // 被移出的原范围内用户：新列表、新发起被拒
        assertThat(catchCode(() -> queryService.queryFormData("g2_narrow", page(1, 10))))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());
        assertThat(catchCode(() -> submitService.submitForm("g2_narrow",
                data("title", "收窄后新发起"), null, null, null)))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());
        // 本人提交记录：合法历史访问保留（详情与更新）
        assertThat(queryService.getRecordDetail("g2_narrow", ownRecordId)).containsKey("title");

        // 收窄后的范围内用户：正向可用
        loginAs(TENANT_1, USER_AFTER_NARROW);
        assertThat(queryService.queryFormData("g2_narrow", page(1, 10)).getTotal()).isEqualTo(1L);
        submitService.submitForm("g2_narrow", data("title", "收窄后范围内提交"), null, null, null);

        // 范围外且非提交人：无流程参与关系时按不存在拒绝；有参与关系时保留记录级访问
        loginAs(TENANT_1, USER_OUT_OF_SCOPE);
        PARTICIPANT_STUB.set(false);
        assertThat(catchCode(() -> queryService.getRecordDetail("g2_narrow", ownRecordId)))
                .isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode());
        PARTICIPANT_STUB.set(true);
        assertThat(queryService.getRecordDetail("g2_narrow", ownRecordId)).containsKey("title");
        PARTICIPANT_STUB.set(false);

        // 拒绝不新增记录：物理行数为 2（原记录 + 收窄后范围内提交）
        assertThat(physicalRowCount(form.getPhysicalTableName())).isEqualTo(2L);
    }

    // ==================== 6. 跨租户 ====================

    @Test
    @DisplayName("跨租户：他租户应用对象与表单均不可访问（指定对象直读拒绝）")
    void crossTenantAppObjectRejected() {
        insertApp("app-t1", TENANT_1, "ALL", "ENABLED");
        createPublishedFormBoundTo("g2_cross", "app-t1");

        loginAs(TENANT_2, USER_OTHER_TENANT);
        // 他租户表单：租户列隔离下表单定义不可见，数据与提交均拒绝
        assertThat(catchCode(() -> queryService.queryFormData("g2_cross", page(1, 10))))
                .isEqualTo(FormErrorCode.QUERY_FORM_NOT_EXIST.getCode());
        assertThat(catchCode(() -> submitService.submitForm("g2_cross",
                data("title", "跨租户提交"), null, null, null)))
                .isEqualTo(FormErrorCode.FORM_NOT_FOUND.getCode());
        // 闸门对跨租户 appId 的判定：不可见（按不存在处理）
        FormAppAccessGuard.AppAccess access = ctx.getBean(FormAppAccessGuard.class).evaluate("app-t1");
        assertThat(access).isEqualTo(FormAppAccessGuard.AppAccess.NOT_VISIBLE);
    }

    // ==================== 7. 全租户（ALL）语义 ====================

    @Test
    @DisplayName("全部用户：任意本租户用户可用；他租户仍拒绝")
    void allScopeAllowsTenantMembers() {
        insertApp("app-all", TENANT_1, "ALL", "ENABLED");
        createPublishedFormBoundTo("g2_all", "app-all");

        loginAs(TENANT_1, USER_OUT_OF_SCOPE);
        submitService.submitForm("g2_all", data("title", "全员可用"), null, null, null);
        assertThat(queryService.queryFormData("g2_all", page(1, 10)).getTotal()).isEqualTo(1L);

        loginAs(TENANT_2, USER_OTHER_TENANT);
        assertThat(catchCode(() -> queryService.queryFormData("g2_all", page(1, 10))))
                .isEqualTo(FormErrorCode.QUERY_FORM_NOT_EXIST.getCode());
    }
}
