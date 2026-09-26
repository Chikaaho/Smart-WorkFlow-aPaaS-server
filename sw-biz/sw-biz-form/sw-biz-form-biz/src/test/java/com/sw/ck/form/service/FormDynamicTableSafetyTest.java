package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.dto.FormDataUpdateRequest;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Phase 3 动态宽表数据安全集成行为证据（真实 H2 + 真实服务链）。
 *
 * <p>逐项对应方向 §5.A / §5.C.2：</p>
 * <ol>
 *   <li>正常 CRUD 与逻辑删除语义保持兼容；</li>
 *   <li>跨租户读写、删除、引用一律不可越界；</li>
 *   <li>非法标识符（表名/子表名）在 SQL 构造边界被拒绝；</li>
 *   <li>元数据扫描、引用检查、目标表访问失败一律 fail closed（旧实现为静默放行/空结果）；</li>
 *   <li>导入导出的 REFERENCE 校验与导出子表读取保持受控与可诊断。</li>
 * </ol>
 */
@SpringBootTest(classes = FormDynamicTableSafetyTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("Phase3 动态宽表数据安全 · H2 行为证据")
class FormDynamicTableSafetyTest {

    private static final Long T1 = 1L;
    private static final Long T1_USER = 11L;
    private static final Long T2 = 2L;
    private static final Long T2_USER = 21L;

    @Configuration
    @org.mybatis.spring.annotation.MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:phase3safety;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }

        @Bean
        public com.sw.ck.common.security.LoginContextProvider loginContextProvider() {
            return new com.sw.ck.common.security.LoginContextProvider() {
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
                com.sw.ck.common.security.LoginContextProvider loginContextProvider) throws Exception {
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
            com.sw.ck.common.config.mybatis.CommonMetaObjectHandler metaObjectHandler =
                    new com.sw.ck.common.config.mybatis.CommonMetaObjectHandler(loginContextProvider);
            metaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f && f.getId() == null) {
                    f.setId(new FormIdGenerator().generate());
                }
            });
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);

            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
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
        public FormDataQueryService formDataQueryService(FormDefService formDefService,
                                                         FormDefMapper formDefMapper,
                                                         FormConfigMapper formConfigMapper,
                                                         JdbcTemplate jdbcTemplate,
                                                         ObjectMapper objectMapper) {
            return new FormDataQueryService(formDefService, formDefMapper, formConfigMapper,
                    jdbcTemplate, objectMapper);
        }

        @Bean
        public FormDataUpdateService formDataUpdateService(FormDefService formDefService,
                                                           FormDefMapper formDefMapper,
                                                           FormConfigMapper formConfigMapper,
                                                           JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper,
                                                           FormFieldValidator formFieldValidator) {
            return new FormDataUpdateService(formDefService, formDefMapper, formConfigMapper,
                    jdbcTemplate, objectMapper, new FormIdGenerator(),
                    mock(DictFacade.class), formFieldValidator);
        }

        @Bean
        public FormDataDeleteService formDataDeleteService(FormDefService formDefService,
                                                           FormDefMapper formDefMapper,
                                                           JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper) {
            return new FormDataDeleteService(formDefService, formDefMapper, jdbcTemplate, objectMapper);
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
                                                   FormFieldEnrichmentService enrichmentService) {
            return new FormSubmitService(formDefMapper, formTraceMapper, dynamicTableManager,
                    new FormIdGenerator(), objectMapper, jdbcTemplate, mock(DictFacade.class),
                    mock(DomainEventPublisher.class), Optional.empty(), formFieldValidator,
                    emptyProvider(), enrichmentService);
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
                                                               com.sw.ck.common.security.LoginContextProvider loginContextProvider) {
            return new FormImportExportService(formDefService, formConfigMapper, formDefMapper,
                    objectMapper, formSubmitService, formFieldValidator, formDataQueryService,
                    transactionTemplate, jdbcTemplate, loginContextProvider, null);
        }

        @SuppressWarnings("unchecked")
        private static <T> ObjectProvider<T> emptyProvider() {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            org.mockito.Mockito.lenient().when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private FormDefService formDefService;
    @org.springframework.beans.factory.annotation.Autowired
    private FormDataQueryService queryService;
    @org.springframework.beans.factory.annotation.Autowired
    private FormDataUpdateService updateService;
    @org.springframework.beans.factory.annotation.Autowired
    private FormDataDeleteService deleteService;
    @org.springframework.beans.factory.annotation.Autowired
    private FormSubmitService submitService;
    @org.springframework.beans.factory.annotation.Autowired
    private FormImportExportService importExportService;
    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    private final List<String> createdTables = new ArrayList<>();
    private final List<String> createdForms = new ArrayList<>();

    @BeforeEach
    void setUp() {
        loginAs(0L, 0L);
        createMetadataTables();
        loginAs(T1, T1_USER);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + table + "\" CASCADE");
            } catch (Exception ignored) {
                // 清理容错
            }
        }
        createdTables.clear();
        createdForms.clear();
    }

    private void createMetadataTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_def (
                    id VARCHAR(36) PRIMARY KEY, form_key VARCHAR(100) NOT NULL UNIQUE,
                    name VARCHAR(200) NOT NULL, logical_table_name VARCHAR(100),
                    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', physical_table_name VARCHAR(100),
                    form_version INT NOT NULL DEFAULT 1, description VARCHAR(500),
                    visibility_scope TEXT, sub_table_mapping TEXT,
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

    private FormDefEntity createPublishedForm(String formKey, String definitionJson) {
        FormDefDTO draft = formDefService.createDraft(formKey, "P3-" + formKey, null, null);
        createdForms.add(draft.getId());
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper().selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());
        String subMapping = entity.getSubTableMapping();
        if (subMapping != null && !subMapping.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> mapping = objectMapper.readValue(subMapping, Map.class);
                createdTables.addAll(mapping.values());
            } catch (Exception ignored) {
                // 子表清理失败不影响断言
            }
        }
        return entity;
    }

    private FormDefMapper formDefMapper() {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                applicationContext().getBean(FormDefMapper.class));
    }

    private org.springframework.context.ApplicationContext applicationContext() {
        return this.ctx;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext ctx;

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // ==================== 1. 正常 CRUD + 逻辑删除 + 跨租户 ====================

    @Test
    @DisplayName("CRUD + 逻辑删除语义兼容；跨租户读写删均不可越界")
    void crudLogicalDeleteAndTenantBoundary() {
        String formKey = "p3_crud";
        createPublishedForm(formKey, """
                {"fields":[{"name":"title","type":"TEXT","label":"标题"}]}
                """);
        String recordId = submitService.submitForm(formKey, data("title", "租户1记录"), null, null, null);
        System.out.println("[P3-CRUD] submitted recordId=" + recordId);

        FormDataQueryRequest req = new FormDataQueryRequest();
        req.setPageNum(1);
        req.setPageSize(10);
        assertThat(queryService.queryFormData(formKey, req).getTotal()).isEqualTo(1L);

        // 更新（乐观锁）
        FormDataUpdateRequest update = new FormDataUpdateRequest();
        update.setData(data("title", "租户1记录-改"));
        update.setVersion(0L);
        updateService.updateRecord(formKey, recordId, update);
        Map<String, Object> detail = queryService.getRecordDetail(formKey, recordId);
        assertThat(detail.get("title")).isEqualTo("租户1记录-改");

        // 跨租户：读不到、改不动、删不动
        loginAs(T2, T2_USER);
        assertThat(queryService.queryFormData(formKey, req).getTotal()).isZero();
        assertThat(catchCode(() -> queryService.getRecordDetail(formKey, recordId)))
                .isEqualTo(FormErrorCode.RECORD_NOT_FOUND.getCode());
        FormDataUpdateRequest crossUpdate = new FormDataUpdateRequest();
        crossUpdate.setData(data("title", "越权改"));
        crossUpdate.setVersion(1L);
        assertThat(catchCode(() -> updateService.updateRecord(formKey, recordId, crossUpdate)))
                .isEqualTo(FormErrorCode.RECORD_NOT_FOUND.getCode());
        deleteService.deleteRecord(formKey, recordId); // 跨租户删除：幂等无操作
        loginAs(T1, T1_USER);
        assertThat(queryService.queryFormData(formKey, req).getTotal()).isEqualTo(1L);

        // 逻辑删除：物理行仍在，deleted=1；查询不再可见
        deleteService.deleteRecord(formKey, recordId);
        String table = currentPhysicalTable(formKey);
        Map<String, Object> raw = jdbcTemplate.queryForMap(
                "SELECT \"deleted\" FROM \"" + table + "\" WHERE \"id\" = ?", recordId);
        System.out.println("[P3-CRUD] after delete physical deleted flag=" + raw.get("deleted")
                + ", table=" + table);
        assertThat(((Number) raw.get("deleted")).intValue()).isEqualTo(1);
        assertThat(queryService.queryFormData(formKey, req).getTotal()).isZero();
        // 重复删除幂等
        deleteService.deleteRecord(formKey, recordId);
    }

    private String currentPhysicalTable(String formKey) {
        return formDefService.getFormDefByKey(formKey).getPhysicalTableName();
    }

    // ==================== 2. 非法标识符 ====================

    @Test
    @DisplayName("非法物理表名：查询/删除在构造边界被拒绝（不进入 SQL）")
    void illegalPhysicalTableNameRejected() {
        String formKey = "p3_illegal_table";
        createPublishedForm(formKey, """
                {"fields":[{"name":"title","type":"TEXT"}]}
                """);
        String recordId = submitService.submitForm(formKey, data("title", "x"), null, null, null);
        FormDefEntity entity = formDefMapper().selectById(createdForms.get(0));
        jdbcTemplate.update("UPDATE sw_form_def SET physical_table_name = ? WHERE id = ?",
                "sw_form_bad\"; DROP TABLE sw_form_def; --", entity.getId());

        FormDataQueryRequest req = new FormDataQueryRequest();
        req.setPageNum(1);
        req.setPageSize(10);
        assertThat(catchCode(() -> queryService.queryFormData(formKey, req)))
                .isEqualTo(FormErrorCode.QUERY_FORM_NOT_EXIST.getCode());
        assertThat(catchCode(() -> deleteService.deleteRecord(formKey, recordId)))
                .isEqualTo(FormErrorCode.QUERY_FORM_NOT_EXIST.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'sw_form_def'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("元数据里的非法子表名：删除被拒绝（fail closed，不静默跳过）")
    void illegalSubTableNameInMetadataRejected() {
        String formKey = "p3_illegal_sub";
        FormDefEntity entity = createPublishedForm(formKey, """
                {"fields":[{"name":"title","type":"TEXT"},{"name":"items","type":"TABLE","subFields":[
                    {"name":"item_name","type":"TEXT"}]}]}
                """);
        String recordId = submitService.submitForm(formKey, data("title", "x",
                "items", List.of(data("item_name", "行1"))), null, null, null);
        jdbcTemplate.update("UPDATE sw_form_def SET sub_table_mapping = ? WHERE id = ?",
                "{\"items\":\"sw_form_table_XXXXXX\",\"other\":\"sw_form_tbl_hijklmnopq\"}", entity.getId());
        assertThat(catchCode(() -> deleteService.deleteRecord(formKey, recordId)))
                .isEqualTo(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE.getCode());
    }

    // ==================== 3. fail closed ====================

    @Test
    @DisplayName("元数据表不可用：删除 fail closed（旧实现为“放行删除”）")
    void deleteFailClosedWhenMetadataUnavailable() {
        String formKey = "p3_meta_down";
        createPublishedForm(formKey, """
                {"fields":[{"name":"title","type":"TEXT"}]}
                """);
        String recordId = submitService.submitForm(formKey, data("title", "x"), null, null, null);
        jdbcTemplate.execute("DROP TABLE sw_form_config");
        try {
            assertThat(catchCode(() -> deleteService.deleteRecord(formKey, recordId)))
                    .isEqualTo(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE.getCode());
            // 记录必须仍然存活（未发生半删）
            assertThat(jdbcTemplate.queryForObject("SELECT \"deleted\" FROM \""
                    + currentPhysicalTable(formKey) + "\" WHERE \"id\" = ?", Integer.class, recordId))
                    .isZero();
            System.out.println("[P3-FAILCLOSED] metadata unavailable → delete refused, record still live");
        } finally {
            recreateFormConfigTable();
        }
    }

    @Test
    @DisplayName("子表不可访问：删除与详情查询双双 fail closed")
    void failClosedWhenSubTableUnreachable() {
        String formKey = "p3_sub_down";
        FormDefEntity entity = createPublishedForm(formKey, """
                {"fields":[{"name":"title","type":"TEXT"},{"name":"items","type":"TABLE","subFields":[
                    {"name":"item_name","type":"TEXT"}]}]}
                """);
        String recordId = submitService.submitForm(formKey, data("title", "x",
                "items", List.of(data("item_name", "行1"))), null, null, null);
        String subTable = subTableOf(entity);
        jdbcTemplate.execute("DROP TABLE \"" + subTable + "\"");

        assertThat(catchCode(() -> queryService.getRecordDetail(formKey, recordId)))
                .isEqualTo(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE.getCode());
        assertThat(catchCode(() -> deleteService.deleteRecord(formKey, recordId)))
                .isEqualTo(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE.getCode());
        System.out.println("[P3-FAILCLOSED] sub-table missing → detail/delete refused (no silent empty)");
    }

    private String subTableOf(FormDefEntity entity) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> mapping = objectMapper.readValue(entity.getSubTableMapping(), Map.class);
            return mapping.get("items");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void recreateFormConfigTable() {
        jdbcTemplate.execute("""
                CREATE TABLE sw_form_config (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL, table_name VARCHAR(200),
                    parent_table VARCHAR(200), definition CLOB NOT NULL,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
    }

    // ==================== 4. REFERENCE 校验 ====================

    @Test
    @DisplayName("REFERENCE：跨租户/不存在/已删除目标一律拒绝；存活目标正常引用")
    void referenceTargetMustBeLiveAndSameTenant() {
        String targetKey = "p3_ref_target";
        createPublishedForm(targetKey, """
                {"fields":[{"name":"name","type":"TEXT","label":"名称"}]}
                """);
        String targetId = submitService.submitForm(targetKey, data("name", "目标记录"), null, null, null);

        String refKey = "p3_ref_holder";
        createPublishedForm(refKey, """
                {"fields":[{"name":"ref_target","type":"REFERENCE","targetFormId":"p3_ref_target","label":"目标"}]}
                """);

        // 正常引用
        String holderId = submitService.submitForm(refKey, data("ref_target", targetId), null, null, null);
        assertThat(holderId).isNotBlank();

        // 不存在目标
        assertThat(catchCode(() -> submitService.submitForm(refKey,
                data("ref_target", "00000000-0000-0000-0000-000000000000"), null, null, null)))
                .isEqualTo(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND.getCode());

        // 跨租户目标
        loginAs(T2, T2_USER);
        assertThat(catchCode(() -> submitService.submitForm(refKey,
                data("ref_target", targetId), null, null, null)))
                .isEqualTo(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND.getCode());
        loginAs(T1, T1_USER);

        // 目标被删除后不得再新增引用
        deleteService.deleteRecord(refKey, holderId);
        deleteService.deleteRecord(targetKey, targetId);
        assertThat(catchCode(() -> submitService.submitForm(refKey,
                data("ref_target", targetId), null, null, null)))
                .isEqualTo(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND.getCode());
        System.out.println("[P3-REF] live/cross-tenant/missing/deleted reference cases all enforced");
    }

    @Test
    @DisplayName("REFERENCE 命中现有引用时删除被 RESTRICT 拒绝")
    void restrictRejectsDeleteWhenReferenced() {
        String targetKey = "p3_restrict_target";
        createPublishedForm(targetKey, """
                {"fields":[{"name":"name","type":"TEXT"}]}
                """);
        String targetId = submitService.submitForm(targetKey, data("name", "被引用"), null, null, null);
        String refKey = "p3_restrict_holder";
        createPublishedForm(refKey, """
                {"fields":[{"name":"ref_target","type":"REFERENCE","targetFormId":"p3_restrict_target"}]}
                """);
        submitService.submitForm(refKey, data("ref_target", targetId), null, null, null);

        assertThat(catchCode(() -> deleteService.deleteRecord(targetKey, targetId)))
                .isEqualTo(FormErrorCode.DELETE_RESTRICT_REFERENCED.getCode());
        assertThat(jdbcTemplate.queryForObject("SELECT \"deleted\" FROM \""
                + currentPhysicalTable(targetKey) + "\" WHERE \"id\" = ?", Integer.class, targetId))
                .isZero();
    }

    // ==================== 5. 导入导出 ====================

    @Test
    @DisplayName("导入：REFERENCE 存活目标成功、失效目标按行拒绝且整批回滚")
    void importReferenceValidation() throws Exception {
        String targetKey = "p3_imp_target";
        createPublishedForm(targetKey, """
                {"fields":[{"name":"name","type":"TEXT","label":"名称"}]}
                """);
        String liveId = submitService.submitForm(targetKey, data("name", "存活目标"), null, null, null);

        String formKey = "p3_imp_form";
        createPublishedForm(formKey, """
                {"fields":[{"name":"title","type":"TEXT","label":"标题"},
                            {"name":"ref_target","type":"REFERENCE","targetFormId":"p3_imp_target","label":"引用"}]}
                """);
        byte[] template = importExportService.generateTemplate(formKey);

        // 行 1：合法引用；行 2：失效引用
        byte[] bad = fillTemplate(template, "模板", new String[][]{
                {"正常行", liveId},
                {"异常行", "00000000-0000-0000-0000-000000000000"}});
        FormImportExportService.ImportResult result =
                importExportService.importData(formKey, new ByteArrayInputStream(bad));
        System.out.println("[P3-IMPORT] total=" + result.totalRows() + " success=" + result.successCount()
                + " errors=" + result.errorCount() + " detail=" + result.errors());
        assertThat(result.successCount()).isZero();
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1)
                .allSatisfy(err -> assertThat(err.message()).contains("不存在或不具备引用权限"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \""
                + currentPhysicalTable(formKey) + "\" WHERE \"deleted\" = 0", Integer.class)).isZero();

        // 全部合法：两条都落库
        byte[] good = fillTemplate(template, "模板", new String[][]{
                {"行1", liveId},
                {"行2", liveId}});
        FormImportExportService.ImportResult ok =
                importExportService.importData(formKey, new ByteArrayInputStream(good));
        assertThat(ok.successCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \""
                + currentPhysicalTable(formKey) + "\" WHERE \"deleted\" = 0", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("导出：引用显示值解析 + 子表 sheet；子表不可访问时 fail closed")
    void exportResolvesReferenceAndFailsClosed() throws Exception {
        String targetKey = "p3_exp_target";
        createPublishedForm(targetKey, """
                {"fields":[{"name":"name","type":"TEXT","label":"名称"}]}
                """);
        String targetId = submitService.submitForm(targetKey, data("name", "显示值来源"), null, null, null);

        String formKey = "p3_exp_form";
        FormDefEntity entity = createPublishedForm(formKey, """
                {"fields":[{"name":"title","type":"TEXT","label":"标题"},
                            {"name":"ref_target","type":"REFERENCE","targetFormId":"p3_exp_target","label":"引用"},
                            {"name":"items","type":"TABLE","label":"明细","subFields":[
                                {"name":"item_name","type":"TEXT","label":"明细名"}]}]}
                """);
        submitService.submitForm(formKey, data("title", "导出行", "ref_target", targetId,
                "items", List.of(data("item_name", "明细1"))), null, null, null);

        FormDataQueryRequest req = new FormDataQueryRequest();
        req.setPageNum(1);
        req.setPageSize(100);
        byte[] xlsx = importExportService.exportData(formKey, req);
        String header;
        String firstDataRow;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("数据");
            header = rowText(sheet.getRow(0));
            firstDataRow = rowText(sheet.getRow(1));
            assertThat(wb.getSheet("items")).as("子表 sheet 应存在").isNotNull();
            System.out.println("[P3-EXPORT] header=" + header + " | row1=" + firstDataRow);
        }
        assertThat(firstDataRow).contains("显示值来源");

        // 子表不可访问 → 导出 fail closed（不得静默空 sheet）
        jdbcTemplate.execute("DROP TABLE \"" + subTableOf(entity) + "\"");
        assertThat(catchCode(() -> importExportService.exportData(formKey, req)))
                .isEqualTo(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE.getCode());
    }

    private static String rowText(Row row) {
        if (row == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(row.getCell(i) == null ? "" : row.getCell(i).toString());
        }
        return sb.toString();
    }

    /**
     * 按模板既有表头（第 1 行显示名 / 第 2 行映射标识）填充数据：数据从 Excel 第 3 行开始。
     *
     * @param rows 每行单元格文本（仅写入数据区，不触碰表头与映射行）
     */
    private byte[] fillTemplate(byte[] template, String sheetName, String[][] rows) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            Sheet sheet = wb.getSheet(sheetName);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.getRow(r + 2);
                if (row == null) {
                    row = sheet.createRow(r + 2);
                }
                for (int c = 0; c < rows[r].length; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                    if (cell == null) {
                        cell = row.createCell(c);
                    }
                    cell.setCellValue(rows[r][c]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private int catchCode(Runnable action) {
        try {
            action.run();
            return -1;
        } catch (BaseException e) {
            return e.getCode();
        }
    }
}
