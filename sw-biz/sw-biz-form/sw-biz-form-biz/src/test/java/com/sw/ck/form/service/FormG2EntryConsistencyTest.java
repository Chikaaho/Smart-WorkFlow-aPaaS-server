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
import com.sw.ck.form.api.dto.ExtQueryResult;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.port.ExtDatasourceQueryPort;
import com.sw.ck.form.api.port.FormRecordAccessPort;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormExtQueryEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormExtQueryMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.mapper.FormTraceMapper;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * G2 剩余入口应用范围一致性——行为证据（H2 PostgreSQL 模式 + 真实租户拦截器）。
 *
 * <p>覆盖续办提示02 的三类入口最小必要断言：</p>
 * <ol>
 *   <li>导入/导出与列表/提交同走既有公共守卫：范围内正向可用；范围外/空集合/停用/跨租户
 *       全部拒绝，导入拒绝后零写入；</li>
 *   <li>模板按其实际披露（仅字段结构、零记录行）与既有模板权限口径处理，不构成数据出口；</li>
 *   <li>非超管受限数据范围（SELF）导出正反向对照：可见范围不放大行范围；</li>
 *   <li>{@code /form/ext/query} 契约租户隔离、停用即拒，输出列受契约 schema 约束
 *       （外部数据面，不承载应用绑定表单业务数据）；</li>
 *   <li>{@code recordOwnerUserId} 归属通道语义锁定：仅受控派发通道跳过新访问闸门，
 *       普通提交（归属人为空）在范围外仍被拒。</li>
 * </ol>
 */
@SpringBootTest(classes = FormG2EntryConsistencyTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("G2 剩余入口一致性·导入/导出/模板/ext查询/归属通道（H2）")
class FormG2EntryConsistencyTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final Long USER_IN_SCOPE = 11L;
    private static final Long USER_OUT_OF_SCOPE = 12L;
    private static final Long USER_SELF_SCOPE = 14L;
    private static final Long USER_OTHER_TENANT = 21L;

    static final AtomicBoolean PARTICIPANT_STUB = new AtomicBoolean(false);
    /** 数据范围桩：导出路径经 LoginContextProvider 解析范围，测试按需切换 SELF/ALL。 */
    static final AtomicReference<com.sw.ck.common.datascope.DataScopeType> PROVIDER_SCOPE =
            new AtomicReference<>(com.sw.ck.common.datascope.DataScopeType.ALL);

    @Configuration
    @org.mybatis.spring.annotation.MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:g2entry;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
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
                    return PROVIDER_SCOPE.get();
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

        @Bean
        public FormImportExportService formImportExportService(FormDefService formDefService,
                                                               FormConfigMapper formConfigMapper,
                                                               FormDefMapper formDefMapper,
                                                               ObjectMapper objectMapper,
                                                               FormSubmitService formSubmitService,
                                                               FormFieldValidator formFieldValidator,
                                                               FormDataQueryService formDataQueryService,
                                                               TransactionTemplate transactionTemplate,
                                                               JdbcTemplate jdbcTemplate,
                                                               LoginContextProvider loginContextProvider) {
            return new FormImportExportService(formDefService, formConfigMapper, formDefMapper,
                    objectMapper, formSubmitService, formFieldValidator, formDataQueryService,
                    transactionTemplate, jdbcTemplate, loginContextProvider, null);
        }

        /** 外部数据源查询桩：返回契约 schema 内的固定行，不触达真实外部库。 */
        @Bean
        public ExtDatasourceQueryPort extDatasourceQueryPort() {
            return (datasourceId, sql, operatorId, operatorName) -> Optional.of(ExtQueryResult.builder()
                    .columns(List.of("value", "display"))
                    .rows(List.of(Map.of("value", "V-1", "display", "对象一")))
                    .rowCount(1)
                    .build());
        }

        @Bean
        public com.sw.ck.form.api.facade.FormDataSubmitFacade formDataSubmitFacade(
                FormSubmitService formSubmitService) {
            return new FormDataSubmitFacadeImpl(formSubmitService);
        }

        @Bean
        public FormExtDataService formExtDataService(FormExtQueryMapper extQueryMapper,
                                                     ObjectProvider<ExtDatasourceQueryPort> queryPort,
                                                     ObjectMapper objectMapper) {
            return new FormExtDataService(extQueryMapper, queryPort, objectMapper);
        }

        @SuppressWarnings("unchecked")
        private static <T> ObjectProvider<T> emptyProvider() {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            org.mockito.Mockito.lenient().when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }
    }

    @Autowired
    private FormImportExportService importExportService;
    @Autowired
    private FormExtDataService extDataService;
    @Autowired
    private FormDataQueryService queryService;
    @Autowired
    private FormSubmitService submitService;
    @Autowired
    private com.sw.ck.form.api.facade.FormDataSubmitFacade submitFacade;
    @Autowired
    private FormDefService formDefService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FormDefMapper formDefMapper;

    private final List<String> createdTables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PARTICIPANT_STUB.set(false);
        PROVIDER_SCOPE.set(com.sw.ck.common.datascope.DataScopeType.ALL);
        LoginUserHolder.clear();
        createMetadataTables();
        loginAs(TENANT_1, USER_IN_SCOPE, DataScope.ALL);
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
    }

    private void loginAs(Long tenantId, Long userId, DataScope scope) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        user.setDataScope(scope);
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

    private FormDefEntity createPublishedFormBoundTo(String formKey, String appId) {
        loginAs(TENANT_1, USER_IN_SCOPE, DataScope.ALL);
        var draft = formDefService.createDraft(formKey, "G2E-" + formKey, null, null);
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

    /** 用服务端生成的模板填充两行数据（模板→导入同一签名链）。 */
    private byte[] buildImportWorkbook(String formKey, String... titles) throws Exception {
        byte[] template = importExportService.generateTemplate(formKey);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            var sheet = workbook.getSheet("模板");
            Row mappingRow = sheet.getRow(1);
            for (int i = 0; i < titles.length; i++) {
                Row dataRow = sheet.createRow(2 + i);
                for (int c = 0; c < mappingRow.getLastCellNum(); c++) {
                    String mappingKey = mappingRow.getCell(c).getStringCellValue();
                    if ("title".equals(mappingKey)) {
                        dataRow.createCell(c).setCellValue(titles[i]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private int exportDataRowCount(String formKey) throws Exception {
        byte[] bytes = importExportService.exportData(formKey, null);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("数据");
            int count = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null && row.getCell(0) != null) {
                    count++;
                }
            }
            return count;
        }
    }

    private FormDefMapper ultimateFormDefMapper() {
        return AopTestUtils.getUltimateTargetObject(formDefMapper);
    }

    private long physicalRowCount(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + table + "\" WHERE \"deleted\" = 0", Long.class);
        return count == null ? 0L : count;
    }

    private void importRows(String formKey, String... titles) throws Exception {
        importExportService.importData(formKey, new ByteArrayInputStream(buildImportWorkbook(formKey, titles)));
    }

    /** 导入拒绝是行级结果（整批回滚、零落库），不抛异常：返回结果供断言。 */
    private FormImportExportService.ImportResult tryImport(String formKey, String... titles) throws Exception {
        return importExportService.importData(formKey,
                new ByteArrayInputStream(buildImportWorkbook(formKey, titles)));
    }

    // ==================== 1. 范围内正向：模板/导入/导出可用 ====================

    @Test
    @DisplayName("范围内用户：模板仅披露结构零记录行，导入两行成功，导出返回两行")
    void inScopeTemplateImportExportWork() throws Exception {
        insertApp("g2e-app", TENANT_1, "SPECIFIED", "ENABLED");
        insertAppUser("g2e-app-u11", TENANT_1, "g2e-app", USER_IN_SCOPE);
        createPublishedFormBoundTo("g2e_pos", "g2e-app");

        // 模板：只有表头两行（显示名 + 映射标识），没有任何记录数据
        byte[] template = importExportService.generateTemplate("g2e_pos");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            var sheet = workbook.getSheet("模板");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("标题");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("title");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
        }

        importRows("g2e_pos", "导入行一", "导入行二");
        assertThat(physicalRowCount(ultimateFormDefMapper()
                .selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.sw.ck.form.entity.FormDefEntity>()
                        .eq(com.sw.ck.form.entity.FormDefEntity::getFormKey, "g2e_pos"))
                .getPhysicalTableName())).isEqualTo(2L);
        assertThat(exportDataRowCount("g2e_pos")).isEqualTo(2);
    }

    // ==================== 2. 范围外：导入/导出拒绝且零副作用 ====================

    @Test
    @DisplayName("范围外用户：导入拒绝后零写入、导出拒绝无数据返回；模板按既有权限口径仍仅披露结构")
    void outOfScopeImportExportRejectedZeroSideEffects() throws Exception {
        insertApp("g2e-app2", TENANT_1, "SPECIFIED", "ENABLED");
        insertAppUser("g2e-app2-u11", TENANT_1, "g2e-app2", USER_IN_SCOPE);
        FormDefEntity form = createPublishedFormBoundTo("g2e_oos", "g2e-app2");
        submitService.submitForm("g2e_oos", Map.of("title", "范围内已有"), null, null, null);

        loginAs(TENANT_1, USER_OUT_OF_SCOPE, DataScope.ALL);
        var importResult = tryImport("g2e_oos", "越界导入");
        assertThat(importResult.successCount()).isZero();
        assertThat(importResult.errorCount()).isEqualTo(1);
        assertThatThrownBy(() -> importExportService.exportData("g2e_oos", null))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode()));

        // 模板：既有模板权限口径处理——只披露字段结构，不含任何记录数据
        byte[] template = importExportService.generateTemplate("g2e_oos");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertThat(workbook.getSheet("模板").getLastRowNum()).isEqualTo(1);
        }

        // 副作用回读：物理行数仍为 1（拒绝零写入）
        assertThat(physicalRowCount(form.getPhysicalTableName())).isEqualTo(1L);
    }

    // ==================== 3. 停用：导入/导出拒绝，本人历史保留 ====================

    @Test
    @DisplayName("停用应用：导入/导出被拒且零写入；本人提交记录详情保留")
    void disabledAppBlocksImportExport() throws Exception {
        insertApp("g2e-app3", TENANT_1, "SPECIFIED", "ENABLED");
        insertAppUser("g2e-app3-u11", TENANT_1, "g2e-app3", USER_IN_SCOPE);
        FormDefEntity form = createPublishedFormBoundTo("g2e_dis", "g2e-app3");
        String recordId = submitService.submitForm("g2e_dis",
                Map.of("title", "停用前记录"), null, null, null);

        jdbcTemplate.update("UPDATE sw_form_app SET status = 'DISABLED' WHERE id = 'g2e-app3'");
        var importResult = tryImport("g2e_dis", "停用后导入");
        assertThat(importResult.successCount()).isZero();
        assertThat(importResult.errorCount()).isEqualTo(1);
        assertThatThrownBy(() -> importExportService.exportData("g2e_dis", null))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.APP_DISABLED.getCode()));
        assertThat(physicalRowCount(form.getPhysicalTableName())).isEqualTo(1L);
        assertThat(queryService.getRecordDetail("g2e_dis", recordId)).containsKey("title");
    }

    // ==================== 4. 空集合与跨租户 ====================

    @Test
    @DisplayName("空集合=无人可见；跨租户表单按不存在拒绝（导入/导出一致）")
    void emptyScopeAndCrossTenantRejected() throws Exception {
        insertApp("g2e-app4", TENANT_1, "SPECIFIED", "ENABLED");
        createPublishedFormBoundTo("g2e_empty", "g2e-app4");
        var emptyResult = tryImport("g2e_empty", "空集导入");
        assertThat(emptyResult.successCount()).isZero();
        assertThat(emptyResult.errorCount()).isEqualTo(1);
        assertThatThrownBy(() -> importExportService.exportData("g2e_empty", null))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode()));

        createPublishedFormBoundTo("g2e_cross", "g2e-app4");
        // 跨租户模板在本租户生成后，他租户身份导入/导出均按不存在拒绝
        loginAs(TENANT_1, USER_IN_SCOPE, DataScope.ALL);
        byte[] crossWorkbook = buildImportWorkbook("g2e_cross", "跨租户导入");
        loginAs(TENANT_2, USER_OTHER_TENANT, DataScope.ALL);
        assertThatThrownBy(() -> importExportService.importData("g2e_cross",
                new ByteArrayInputStream(crossWorkbook)))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.FORM_NOT_FOUND.getCode()));
        assertThatThrownBy(() -> importExportService.exportData("g2e_cross", null))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.FORM_NOT_FOUND.getCode()));
    }

    // ==================== 5. 非超管受限范围导出对照 ====================

    @Test
    @DisplayName("非超管 SELF 范围：导出仅本人行；ALL 范围用户导出全量（可见性不放大数据范围）")
    void exportRespectsRestrictedDataScope() throws Exception {
        insertApp("g2e-app5", TENANT_1, "SPECIFIED", "ENABLED");
        insertAppUser("g2e-app5-u11", TENANT_1, "g2e-app5", USER_IN_SCOPE);
        insertAppUser("g2e-app5-u14", TENANT_1, "g2e-app5", USER_SELF_SCOPE);
        createPublishedFormBoundTo("g2e_scope", "g2e-app5");

        loginAs(TENANT_1, USER_SELF_SCOPE, DataScope.SELF);
        submitService.submitForm("g2e_scope", Map.of("title", "SELF自己的"), null, null, null);
        loginAs(TENANT_1, USER_IN_SCOPE, DataScope.ALL);
        submitService.submitForm("g2e_scope", Map.of("title", "ALL用户的"), null, null, null);

        // SELF 用户导出：只见本人 1 行（导出范围经 LoginContextProvider 解析）
        loginAs(TENANT_1, USER_SELF_SCOPE, DataScope.SELF);
        PROVIDER_SCOPE.set(com.sw.ck.common.datascope.DataScopeType.SELF);
        byte[] selfExport = importExportService.exportData("g2e_scope", null);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(selfExport))) {
            var sheet = workbook.getSheet("数据");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("SELF自己的");
        }
        // ALL 用户导出：2 行全量
        PROVIDER_SCOPE.set(com.sw.ck.common.datascope.DataScopeType.ALL);
        loginAs(TENANT_1, USER_IN_SCOPE, DataScope.ALL);
        assertThat(exportDataRowCount("g2e_scope")).isEqualTo(2);
    }

    // ==================== 6. ext 查询：租户隔离 + 停用即拒 + schema 约束 ====================

    @Test
    @DisplayName("ext 查询：本租户启用契约可执行且输出限契约列；停用即拒；跨租户 queryKey 不可见")
    void extQueryTenantIsolatedAndEnablementGated() {
        loginAs(TENANT_1, USER_IN_SCOPE, DataScope.ALL);
        FormExtQueryEntity entity = extDataService.registerQuery(9L, "g2e_contract",
                "SELECT id AS value, name AS display FROM ext_obj",
                List.of(Map.of("name", "value"), Map.of("name", "display")));
        assertThat(entity.getQueryVersion()).isEqualTo(1);

        ExtQueryResult result = extDataService.preview("g2e_contract", null);
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getRows().get(0)).containsOnlyKeys("value", "display");

        // 停用即拒
        jdbcTemplate.update("UPDATE sw_form_ext_query SET enabled = 0 WHERE id = ?", entity.getId());
        assertThatThrownBy(() -> extDataService.preview("g2e_contract", null))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.EXT_QUERY_DISABLED.getCode()));

        // 跨租户同名 queryKey 不可见（租户列隔离 → 按不存在拒绝）
        jdbcTemplate.update("UPDATE sw_form_ext_query SET enabled = 1 WHERE id = ?", entity.getId());
        loginAs(TENANT_2, USER_OTHER_TENANT, DataScope.ALL);
        assertThatThrownBy(() -> extDataService.preview("g2e_contract", null))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.EXT_QUERY_NOT_FOUND.getCode()));
    }

    // ==================== 7. recordOwnerUserId 归属通道边界 ====================

    @Test
    @DisplayName("归属通道：仅受控 submitOnBehalfOf（派发归属）跳过新访问闸门；普通提交同上下文仍被拒")
    void recordOwnerChannelBoundary() {
        insertApp("g2e-app7", TENANT_1, "SPECIFIED", "ENABLED");
        insertAppUser("g2e-app7-u11", TENANT_1, "g2e-app7", USER_IN_SCOPE);
        FormDefEntity form = createPublishedFormBoundTo("g2e_owner", "g2e-app7");

        loginAs(TENANT_1, USER_OUT_OF_SCOPE, DataScope.ALL);
        // 普通提交（归属人为空）：仍被新访问闸门拒绝
        assertThatThrownBy(() -> submitService.submitForm("g2e_owner",
                Map.of("title", "普通越界"), null, null, null))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(FormErrorCode.APP_NOT_FOUND.getCode()));
        // 受控归属通道（系统派发，recordOwnerUserId 非空）：按契约放行
        String recordId = submitFacade.submitOnBehalfOf("g2e_owner",
                Map.of("title", "派发归属"), "g2e-owner-key-1", 99L).orElseThrow();
        assertThat(recordId).isNotBlank();

        // 归属通道落库的 create_by = 归属人（被分配办理人），不是调用上下文用户
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT \"create_by\" FROM \"" + form.getPhysicalTableName()
                        + "\" WHERE \"id\" = ?", recordId);
        assertThat(((Number) row.get("create_by")).longValue()).isEqualTo(99L);
    }
}
