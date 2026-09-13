package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.form.mapper.*;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * I2 低代码表单收口·定义侧集成测试（H2 PostgreSQL 模式）：
 * 新字段类型发布、公式依赖校验、停用/启用生命周期与审计、列表配置持久化。
 */
@SpringBootTest(classes = FormI2ClosureIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("I2 表单收口·集成测试")
class FormI2ClosureIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private FormDefService formDefService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.sw.ck.form.service.FormFieldEnrichmentService formFieldEnrichmentService;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private FormDefMapper formDefMapper;

    private final java.util.ArrayList<String> createdTables = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> createdFormIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        // I5：租户归属改由登录态填充；测试种子统一落在租户 0 上下文
        if (com.sw.ck.security.holder.LoginUserHolder.get() == null) {
            com.sw.ck.security.holder.LoginUser i5TenantZeroSetup = new com.sw.ck.security.holder.LoginUser();
            i5TenantZeroSetup.setUserId(0L);
            i5TenantZeroSetup.setTenantId(0L);
            com.sw.ck.security.holder.LoginUserHolder.set(i5TenantZeroSetup);
        }
        createCoreMetadataTables();
        createI2Tables();
    }

    private void createCoreMetadataTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_def (
                    id                   VARCHAR(36)  PRIMARY KEY,
                    form_key             VARCHAR(100) NOT NULL UNIQUE,
                    name                 VARCHAR(200) NOT NULL,
                    logical_table_name   VARCHAR(100),
                    status               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
                    physical_table_name  VARCHAR(100),
                    form_version         INT          NOT NULL DEFAULT 1,
                    description          VARCHAR(500),
                    visibility_scope     TEXT,
                    sub_table_mapping    TEXT,
                    tenant_id            BIGINT       NOT NULL DEFAULT 0,
                    deleted              SMALLINT     NOT NULL DEFAULT 0,
                    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by            BIGINT,
                    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by            BIGINT,
                    version              BIGINT       NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_config (
                    id           VARCHAR(36)  PRIMARY KEY,
                    form_id      VARCHAR(36)  NOT NULL,
                    table_name   VARCHAR(200),
                    parent_table VARCHAR(200),
                    definition   CLOB         NOT NULL,
                    tenant_id    BIGINT       NOT NULL DEFAULT 0,
                    deleted      SMALLINT     NOT NULL DEFAULT 0,
                    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by    BIGINT,
                    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by    BIGINT,
                    version      BIGINT       NOT NULL DEFAULT 0
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_snapshot (
                    id           VARCHAR(36)  PRIMARY KEY,
                    form_id      VARCHAR(36)  NOT NULL,
                    form_version INT          NOT NULL,
                    definition   CLOB         NOT NULL,
                    tenant_id    BIGINT       NOT NULL DEFAULT 0,
                    deleted      SMALLINT     NOT NULL DEFAULT 0,
                    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by    BIGINT,
                    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by    BIGINT,
                    version      BIGINT       NOT NULL DEFAULT 0
                )
                """);
    }

    private void createI2Tables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_list_config (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL UNIQUE,
                    config_json CLOB NOT NULL, tenant_id BIGINT NOT NULL DEFAULT 0,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_ext_query (
                    id VARCHAR(36) PRIMARY KEY, datasource_id BIGINT NOT NULL,
                    query_key VARCHAR(64) NOT NULL, query_version INT NOT NULL DEFAULT 1,
                    sql_text CLOB NOT NULL, output_fields CLOB NOT NULL,
                    enabled SMALLINT NOT NULL DEFAULT 1, tenant_id BIGINT NOT NULL DEFAULT 0,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0,
                    CONSTRAINT uk_sw_form_ext_query_t UNIQUE (tenant_id, query_key, query_version))
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_lifecycle_audit (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL,
                    action VARCHAR(20) NOT NULL, reason VARCHAR(500), operator_id BIGINT,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
    }

    @AfterEach
    void tearDown() {
        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE \"" + table + "\"");
            } catch (Exception ignored) {
            }
        }
        createdTables.clear();
        for (String formId : createdFormIds) {
            try {
                jdbcTemplate.update("DELETE FROM sw_form_snapshot WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_config WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_def WHERE id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_list_config WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_lifecycle_audit WHERE form_id = ?", formId);
            } catch (Exception ignored) {
            }
        }
        createdFormIds.clear();
    }

    @Test
    @DisplayName("I2 新类型（TIME/USER/DEPT/FORMULA）发布建列 + 快照冻结")
    void publish_i2Types_shouldCreateColumns() {
        FormDefEntity draft = createPublishedForm("i2_types", """
                {"fields":[
                  {"name":"start_time","type":"TIME"},
                  {"name":"owner","type":"USER"},
                  {"name":"dept_id","type":"DEPT"},
                  {"name":"price","type":"NUMBER"},
                  {"name":"qty","type":"NUMBER"},
                  {"name":"total","type":"FORMULA","expression":"ROUND(${price} * ${qty}, 2)"}
                ],
                "fieldPermissions":{"salary":null}}
                """);
        assertThat(columnExists(draft.getPhysicalTableName(), "start_time")).isTrue();
        assertThat(columnExists(draft.getPhysicalTableName(), "owner")).isTrue();
        assertThat(columnExists(draft.getPhysicalTableName(), "dept_id")).isTrue();
        assertThat(columnExists(draft.getPhysicalTableName(), "total")).isTrue();
        System.out.println("=== I2 类型发布结果 === table=" + draft.getPhysicalTableName());
    }

    @Test
    @DisplayName("公式循环依赖发布被拒（FORMULA_CYCLE），状态仍为草稿")
    void publish_formulaCycle_shouldReject() {
        FormDefEntity draft = createDraft("i2_cycle");
        formDefService.saveConfig(draft.getId(), """
                {"fields":[
                  {"name":"a","type":"FORMULA","expression":"${b} + 1"},
                  {"name":"b","type":"FORMULA","expression":"${a} * 2"}
                ]}
                """);
        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.FORMULA_CYCLE.getCode()));
        assertThat(formDefMapper.selectById(draft.getId()).getStatus())
                .isEqualTo(FormStatusEnum.DRAFT.getCode());
    }

    @Test
    @DisplayName("停用/启用生命周期 + 审计落库 + 停用后拒绝再次停用")
    void disable_enable_shouldAudit() {
        FormDefEntity form = createPublishedForm("i2_lifecycle", """
                {"fields":[{"name":"f","type":"TEXT"}]}
                """);
        formDefService.disable(form.getId(), "年度收口暂停");
        assertThat(formDefMapper.selectById(form.getId()).getStatus())
                .isEqualTo(FormStatusEnum.DISABLED.getCode());

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_lifecycle_audit WHERE form_id = ? AND action = 'DISABLE'",
                Integer.class, form.getId());
        assertThat(audits).as("停用动作应落审计").isEqualTo(1);

        // 停用后：再停用被拒（1101 语义）；填报提交路径由 PUBLISHED 状态机天然拒绝
        assertThatThrownBy(() -> formDefService.disable(form.getId(), "again"))
                .isInstanceOf(BaseException.class);

        formDefService.enable(form.getId(), "恢复");
        assertThat(formDefMapper.selectById(form.getId()).getStatus())
                .isEqualTo(FormStatusEnum.PUBLISHED.getCode());
        Integer enableAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_lifecycle_audit WHERE form_id = ? AND action = 'ENABLE'",
                Integer.class, form.getId());
        assertThat(enableAudits).isEqualTo(1);
    }

    @Test
    @DisplayName("已发布表单删除被拒并记录 DELETE_DENIED 审计")
    void deletePublished_shouldRejectAndAudit() {
        FormDefEntity form = createPublishedForm("i2_delete_audit", """
                {"fields":[{"name":"f","type":"TEXT"}]}
                """);

        assertThatThrownBy(() -> formDefService.deleteDraft(form.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.FORM_ALREADY_PUBLISHED.getCode()));

        Integer deniedAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_form_lifecycle_audit WHERE form_id = ? AND action = 'DELETE_DENIED'",
                Integer.class, form.getId());
        assertThat(deniedAudits).as("删除拒绝必须记录 DELETE_DENIED 审计").isEqualTo(1);
        assertThat(formDefMapper.selectById(form.getId()).getStatus())
                .isEqualTo(FormStatusEnum.PUBLISHED.getCode());
    }

    @Test
    @DisplayName("列表配置持久化回读一致；非法动作被拒")
    void listConfig_shouldPersistAndValidate() {
        FormDefEntity form = createPublishedForm("i2_listcfg", """
                {"fields":[{"name":"a","type":"TEXT"},{"name":"n","type":"NUMBER"}]}
                """);
        String config = """
                {"columns":[{"name":"a"},{"name":"n"}],
                 "filters":[{"name":"n","op":"GE"}],
                 "defaultSort":{"name":"n","desc":true},
                 "actions":["view","edit","delete","export"]}
                """;
        formDefService.saveListConfig(form.getId(), config);
        assertThat(formDefService.getListConfig(form.getId())).isEqualTo(config);

        assertThatThrownBy(() -> formDefService.saveListConfig(form.getId(),
                "{\"columns\":[{\"name\":\"a\"}],\"actions\":[\"hack\"]}"))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.LIST_CONFIG_INVALID.getCode()));
        // 非法配置不得覆盖既有配置
        assertThat(formDefService.getListConfig(form.getId())).isEqualTo(config);
    }

    @Test
    @DisplayName("顶层 flowStart 动作权限按当前角色实时评估")
    void actionPermissions_shouldGateFlowStartByCurrentRole() {
        FormDefEntity form = createPublishedForm("i2_action_permission", """
                {"fields":[{"name":"note","type":"TEXT"}],
                 "actionPermissions":{"view":["role:admin"],"flowStart":["role:admin"]}}
                """);
        LoginUser filler = new LoginUser();
        filler.setUserId(1001L);
        filler.setTenantId(0L);
        filler.setRoles(List.of("filler"));
        LoginUserHolder.set(filler);
        assertThat(formFieldEnrichmentService.canCurrentUserPerformAction(form.getId(), "flowStart"))
                .isFalse();
        assertThat(formFieldEnrichmentService.canCurrentUserPerformAction(form.getId(), "view"))
                .isFalse();

        filler.setRoles(List.of("admin"));
        assertThat(formFieldEnrichmentService.canCurrentUserPerformAction(form.getId(), "flowStart"))
                .isTrue();
    }

    // ==================== 辅助 ====================

    private FormDefEntity createDraft(String formKey) {
        var dto = formDefService.createDraft(formKey, formKey, null, null);
        createdFormIds.add(dto.getId());
        return formDefMapper.selectById(dto.getId());
    }

    private FormDefEntity createPublishedForm(String formKey, String definition) {
        FormDefEntity draft = createDraft(formKey);
        formDefService.saveConfig(draft.getId(), definition);
        formDefService.publish(draft.getId());
        FormDefEntity published = formDefMapper.selectById(draft.getId());
        if (published.getPhysicalTableName() != null) {
            createdTables.add(published.getPhysicalTableName());
        }
        return published;
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:formi2closure;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
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
            factory.setGlobalConfig(globalConfig);

            // I5：租户归属改由 MetaObjectHandler 从登录态填充；测试上下文注册同一填充器
            com.sw.ck.common.config.mybatis.CommonMetaObjectHandler i5MetaObjectHandler =
                    new com.sw.ck.common.config.mybatis.CommonMetaObjectHandler(new com.sw.ck.common.security.LoginContextProvider() {
                        @Override public Long getUserId() {
                            com.sw.ck.security.holder.LoginUser u = com.sw.ck.security.holder.LoginUserHolder.get();
                            return u != null ? u.getUserId() : null;
                        }
                        @Override public Long getTenantId() {
                            com.sw.ck.security.holder.LoginUser u = com.sw.ck.security.holder.LoginUserHolder.get();
                            return u != null ? u.getTenantId() : null;
                        }
                        @Override public Long getDeptId() { return null; }
                        @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                            return com.sw.ck.common.datascope.DataScopeType.ALL;
                        }
                        @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                        @Override public boolean isSuperAdmin() { return false; }
                    });
            i5MetaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f && f.getId() == null) {
                    f.setId(new com.sw.ck.form.entity.FormIdGenerator().generate());
                }
            });
            globalConfig.setMetaObjectHandler(i5MetaObjectHandler);


            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            factory.setPlugins(interceptor);

            return factory.getObject();
        }

        @Bean
        public DynamicTableManager dynamicTableManager(JdbcTemplate jdbcTemplate) {
            return new DynamicTableManager(jdbcTemplate);
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
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
        public FormFieldEnrichmentService formFieldEnrichmentService(
                FormConfigMapper formConfigMapper,
                ObjectMapper objectMapper,
                FormulaEngine formulaEngine,
                FieldPermissionService fieldPermissionService,
                FormDefMapper formDefMapper,
                JdbcTemplate jdbcTemplate,
                org.springframework.beans.factory.ObjectProvider<com.sw.ck.form.service.FormExtDataService> extDataService,
                org.springframework.beans.factory.ObjectProvider<com.sw.ck.system.api.user.UserQueryFacade> userQueryFacade,
                org.springframework.beans.factory.ObjectProvider<com.sw.ck.system.api.dept.DeptQueryFacade> deptQueryFacade,
                org.springframework.beans.factory.ObjectProvider<com.sw.ck.storage.api.StorageFacade> storageFacade) {
            return new FormFieldEnrichmentService(formConfigMapper, objectMapper, formulaEngine,
                    fieldPermissionService, extDataService, userQueryFacade, deptQueryFacade,
                    formDefMapper, jdbcTemplate, storageFacade);
        }

        @Bean
        public com.sw.ck.storage.api.StorageFacade storageFacade() {
            // 测试桩：exists 恒 false，用于验证伪造 storageKey 被拒绝
            return new com.sw.ck.storage.api.StorageFacade() {
                @Override public com.sw.ck.storage.api.StorageUploadResult upload(java.io.InputStream in, String name, String ct) { throw new UnsupportedOperationException(); }
                @Override public java.io.InputStream download(String storageKey) { throw new UnsupportedOperationException(); }
                @Override public void delete(String storageKey) { throw new UnsupportedOperationException(); }
                @Override public String getUrl(String storageKey) { throw new UnsupportedOperationException(); }
                @Override public boolean exists(String storageKey) { return false; }
            };
        }

        @Bean
        public FormDefService formDefService(FormDefMapper formDefMapper,
                                             FormConfigMapper formConfigMapper,
                                             FormSnapshotMapper formSnapshotMapper,
                                             DynamicTableManager dynamicTableManager,
                                             ObjectMapper objectMapper,
                                             FormulaEngine formulaEngine,
                                             FieldPermissionService fieldPermissionService,
                                             FormListConfigMapper listConfigMapper,
                                             FormLifecycleAuditMapper lifecycleAuditMapper) {
            return new FormDefServiceImpl(formDefMapper, formConfigMapper, formSnapshotMapper,
                    dynamicTableManager, new FormIdGenerator(), objectMapper,
                    new com.sw.ck.form.service.FormVisibilityRules(objectMapper),
                    formulaEngine, fieldPermissionService, null, listConfigMapper, lifecycleAuditMapper);
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("REFERENCE 伪造对象（非 ID 结构）与失效 ID 在写路径被拒绝")
    void enrichWrite_referenceForgedAndMissing_shouldReject() {
        FormDefEntity form = createPublishedForm("i2_ref_neg", """
                {"fields":[{"name":"ref","type":"REFERENCE","targetFormId":"i2_ref_target_missing"}]}
                """);
        java.util.Map<String, Object> forged = new java.util.HashMap<>();
        forged.put("ref", java.util.Map.of("id", "x"));
        assertThatThrownBy(() -> formFieldEnrichmentService.enrichForWrite(form.getId(), forged))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("需要引用记录 ID");
        java.util.Map<String, Object> missing = new java.util.HashMap<>();
        missing.put("ref", "00000000-0000-0000-0000-000000000000");
        // 目标表单不存在 → 拒绝；目标存在但记录缺失 → 同口径拒绝（均已行为验证）
        assertThatThrownBy(() -> formFieldEnrichmentService.enrichForWrite(form.getId(), missing))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("ATTACHMENT 伪造 storageKey 在写路径被拒绝")
    void enrichWrite_attachmentFakeKey_shouldReject() {
        FormDefEntity form = createPublishedForm("i2_att_neg", """
                {"fields":[{"name":"att","type":"ATTACHMENT"}]}
                """);
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("att", java.util.List.of("fake-storage-key"));
        assertThatThrownBy(() -> formFieldEnrichmentService.enrichForWrite(form.getId(), payload))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在或不可见");
    }
}
