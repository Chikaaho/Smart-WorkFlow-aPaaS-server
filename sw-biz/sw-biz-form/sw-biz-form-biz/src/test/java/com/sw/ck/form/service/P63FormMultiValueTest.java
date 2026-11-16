package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import com.sw.ck.form.api.port.FlowStartPort;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.mapper.FormTraceMapper;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.api.dict.DictTypeDetailDTO;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * P63 表单多选人员/部门与日期时间集成测试。
 * <p>
 * 在 H2（PostgreSQL 模式）验证：USER/DEPT 单选/多选列宽与存储形状、DATE format=datetime 转换、
 * 表格列 USER/DEPT 多选、显式租户系统读取与 {@link FormRecordReadFacade} 解码（含稳定行 id）。
 * </p>
 */
@SpringBootTest(classes = P63FormMultiValueTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("P63 表单多值基础·集成测试")
class P63FormMultiValueTest {

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private FormSubmitService formSubmitService;

    @Autowired
    private FormFieldValidator formFieldValidator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FormDefMapper formDefMapper;

    private final ArrayList<String> createdTables = new ArrayList<>();
    private final ArrayList<String> createdFormIds = new ArrayList<>();

    private static final Long TEST_TENANT_ID = 100L;

    @BeforeEach
    void setUp() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setTenantId(TEST_TENANT_ID);
        loginUser.setUsername("p63_user");
        LoginUserHolder.set(loginUser);
        createMetadataTables();
    }

    private void createMetadataTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_def (
                    id                   VARCHAR(36)  PRIMARY KEY,

                    form_type           varchar(20)  not null default 'BUSINESS',                    form_key             VARCHAR(100) NOT NULL UNIQUE,
                    name                 VARCHAR(200) NOT NULL,
                    logical_table_name   VARCHAR(100),
                    status               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
                    physical_table_name  VARCHAR(100),
                    form_version         INT          NOT NULL DEFAULT 1,
                    description          VARCHAR(500),
                    visibility_scope     TEXT,
                    sub_table_mapping    TEXT,
                    app_id               varchar(36),
                    dir_id               varchar(36),
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
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_trace (
                    id                     VARCHAR(36)  PRIMARY KEY,
                    form_id                VARCHAR(36)  NOT NULL,
                    record_id              VARCHAR(36)  NOT NULL,
                    submit_user_id         BIGINT       NOT NULL,
                    submit_ip              VARCHAR(200),
                    submit_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    device_fingerprint     VARCHAR(200),
                    user_agent             VARCHAR(500),
                    submit_idempotency_key VARCHAR(128),
                    tenant_id              BIGINT       NOT NULL DEFAULT 0,
                    deleted                SMALLINT     NOT NULL DEFAULT 0,
                    create_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by              BIGINT,
                    update_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by              BIGINT,
                    version                BIGINT       NOT NULL DEFAULT 0
                )
                """);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE \"" + table + "\" CASCADE");
            } catch (Exception ignored) {
            }
        }
        createdTables.clear();
        for (String formId : createdFormIds) {
            try {
                jdbcTemplate.update("DELETE FROM sw_form_trace WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_snapshot WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_config WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_def WHERE id = ?", formId);
            } catch (Exception ignored) {
            }
        }
        createdFormIds.clear();
    }

    private FormDefDTO publishForm(String formKey, String definitionJson) {
        FormDefDTO draft = formDefService.createDraft(formKey, "P63 多值测试", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        assertThat(entity.getPhysicalTableName()).isNotNull();
        createdTables.add(entity.getPhysicalTableName());
        return draft;
    }

    private static final String DEFINITION = """
            {
                "title": "P63 多值表单",
                "fields": [
                    {"name": "owner", "type": "USER", "label": "负责人"},
                    {"name": "watchers", "type": "USER", "label": "关注人", "multiple": true},
                    {"name": "dept_list", "type": "DEPT", "label": "部门集合", "multiple": true},
                    {"name": "plan_time", "type": "DATE", "label": "预约时间", "format": "datetime"},
                    {"name": "plan_date", "type": "DATE", "label": "普通日期"},
                    {"name": "items", "type": "TABLE", "label": "明细", "subFields": [
                        {"name": "handlers", "type": "USER", "label": "处理人", "multiple": true},
                        {"name": "dept_refs", "type": "DEPT", "label": "相关部门", "multiple": true}
                    ]}
                ]
            }
            """;

    @Test
    @DisplayName("USER/DEPT 多选列宽 VARCHAR(1000)、单选 VARCHAR(64)；提交落 JSON 数组串、datetime 转 LocalDateTime")
    void multiValueColumnsAndStorage() throws Exception {
        FormDefDTO draft = publishForm("p63_multi_value", DEFINITION);
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        String tableName = entity.getPhysicalTableName();

        // 列宽：多选 VARCHAR(1000)，单选 VARCHAR(64)（H2 information_schema 报 CHARACTER VARYING，仅断言长度）
        String userColLen = jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = 'owner'", String.class, tableName);
        String watchersColLen = jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = 'watchers'", String.class, tableName);
        String deptListColLen = jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = 'dept_list'", String.class, tableName);
        assertThat(userColLen).isEqualTo("64");
        assertThat(watchersColLen).isEqualTo("1000");
        assertThat(deptListColLen).isEqualTo("1000");

        // 提交：多选列表 + datetime
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("owner", "5");
        formData.put("watchers", List.of("5", "6"));
        formData.put("dept_list", List.of(7L, 8L));
        formData.put("plan_time", "2026-10-20 14:30");
        formData.put("plan_date", "2026-10-20");
        formData.put("items", List.of(
                Map.of("handlers", List.of("5", "6"), "dept_refs", List.of("7"))));
        String recordId = formSubmitService.submitForm(formKey2(draft), formData, "127.0.0.1", null, "test");

        Map<String, Object> row = jdbcTemplate.queryForList(
                "SELECT * FROM \"" + tableName + "\" WHERE \"id\" = ?", recordId).get(0);
        assertThat(row.get("owner")).isEqualTo("5");
        assertThat(row.get("watchers")).isEqualTo("[\"5\",\"6\"]");
        assertThat(row.get("dept_list")).isEqualTo("[7,8]");
        // datetime 解析为 LocalDateTime（H2 返回 Timestamp/LocalDateTime，统一断言文本含时分）
        assertThat(String.valueOf(row.get("plan_time"))).contains("14:30");
        assertThat(String.valueOf(row.get("plan_date"))).startsWith("2026-10-20");

        // 子表：多选 JSON 串（子表名从 sub_table_mapping 元数据解析）
        String subTableMapping = (String) formDefMapper.selectById(draft.getId()).getSubTableMapping();
        assertThat(subTableMapping).contains("items");
        String subTableName = JsonMapper.builder().build()
                .readTree(subTableMapping).get("items").asText();
        createdTables.add(subTableName);
        List<Map<String, Object>> subRows = jdbcTemplate.queryForList(
                "SELECT * FROM \"" + subTableName + "\" WHERE \"parent_record_id\" = ?", recordId);
        assertThat(subRows).hasSize(1);
        assertThat(subRows.get(0).get("handlers")).isEqualTo("[\"5\",\"6\"]");
        assertThat(subRows.get(0).get("dept_refs")).isEqualTo("[\"7\"]");
        assertThat(subRows.get(0).get("id")).isNotNull();
    }

    private String formKey2(FormDefDTO draft) {
        return draft.getFormKey();
    }

    @Test
    @DisplayName("多选元素含非数字 ID → 1402 类型错误；空集合 → 1402")
    void multiValueValidationRejects() {
        publishForm("p63_multi_invalid", DEFINITION);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("owner", "5");
        bad.put("watchers", List.of("5", "管理员"));
        bad.put("dept_list", List.of("7"));
        bad.put("plan_time", "2026-10-20 14:30");
        bad.put("plan_date", "2026-10-20");
        assertThatThrownBy(() -> formSubmitService.submitForm("p63_multi_invalid", bad,
                "127.0.0.1", null, "test"))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(1402));

        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("owner", "5");
        empty.put("watchers", List.of());
        empty.put("dept_list", List.of("7"));
        empty.put("plan_time", "2026-10-20 14:30");
        empty.put("plan_date", "2026-10-20");
        assertThatThrownBy(() -> formSubmitService.submitForm("p63_multi_invalid", empty,
                "127.0.0.1", null, "test"))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(1402));
    }

    @Test
    @DisplayName("系统读取（显式租户）返回全量字段+子表行 id；Facade 解码多选为 ID 列表、datetime 为 ISO 文本")
    void systemReadAndFacadeDecode() {
        FormDefDTO draft = publishForm("p63_system_read", DEFINITION);
        String formKey = draft.getFormKey();
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("owner", "5");
        formData.put("watchers", List.of("5", "6"));
        formData.put("dept_list", List.of("7", "8"));
        formData.put("plan_time", "2026-10-20 14:30");
        formData.put("plan_date", "2026-10-20");
        formData.put("items", List.of(
                Map.of("handlers", List.of("5"), "dept_refs", List.of("7")),
                Map.of("handlers", List.of("6"), "dept_refs", List.of("8"))));
        String recordId = formSubmitService.submitForm(formKey, formData, "127.0.0.1", null, "test");

        FormDataQueryService queryService = new FormDataQueryService(
                formDefService, formDefMapper, configMapper(), jdbcTemplate, objectMapper());
        Map<String, Object> raw = queryService.getRecordDetailForSystem(TEST_TENANT_ID, formKey, recordId);
        assertThat(raw.get("id")).isEqualTo(recordId);
        assertThat(raw.get("items")).isInstanceOf(List.class);
        assertThat((List<?>) raw.get("items")).hasSize(2);

        FormRecordReadFacade facade = new FormRecordReadFacadeImpl(
                queryService, formDefService, formFieldValidator, objectMapper());
        FormRecordReadFacade.FormRecordData data =
                facade.findRecord(TEST_TENANT_ID, formKey, recordId).orElseThrow();
        assertThat(data.fields().get("owner")).isEqualTo("5");
        assertThat(data.fields().get("watchers")).isEqualTo(List.of("5", "6"));
        assertThat(data.fields().get("dept_list")).isEqualTo(List.of("7", "8"));
        assertThat(String.valueOf(data.fields().get("plan_time"))).contains("14:30");
        List<Map<String, Object>> rows = data.tables().get("items");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("id")).isNotNull();
        assertThat(rows.get(0).get("handlers")).isEqualTo(List.of("5"));
        assertThat(rows.get(1).get("handlers")).isEqualTo(List.of("6"));

        // 跨租户读取 → empty（显式租户条件，不串租户）
        assertThat(facade.findRecord(TEST_TENANT_ID + 1, formKey, recordId)).isEmpty();
        assertThat(facade.findRecord(TEST_TENANT_ID, formKey, "not-exist")).isEmpty();
    }

    @Autowired
    private FormConfigMapper configMapper;

    private FormConfigMapper configMapper() {
        return configMapper;
    }

    @Autowired
    private ObjectMapper beanObjectMapper;

    private ObjectMapper objectMapper() {
        return beanObjectMapper;
    }

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:p63multivalue;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
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

            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            factory.setPlugins(interceptor);

            LoginContextProvider loginContextProvider =
                    new LoginContextProvider() {
                        @Override
                        public Long getUserId() {
                            LoginUser user = LoginUserHolder.get();
                            return user != null ? user.getUserId() : null;
                        }

                        @Override
                        public Long getTenantId() {
                            LoginUser user = LoginUserHolder.get();
                            return user != null ? user.getTenantId() : null;
                        }

                        @Override
                        public Long getDeptId() {
                            return null;
                        }

                        @Override
                        public DataScopeType getDataScopeType() {
                            return DataScopeType.ALL;
                        }

                        @Override
                        public Set<Long> getCustomDeptIds() {
                            return Set.of();
                        }

                        @Override
                        public boolean isSuperAdmin() {
                            return false;
                        }
                    };
            CommonMetaObjectHandler metaObjectHandler =
                    new CommonMetaObjectHandler(loginContextProvider);
            metaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof FormBaseEntity f && f.getId() == null) {
                    f.setId(new FormIdGenerator().generate());
                }
            });
            globalConfig.setMetaObjectHandler(metaObjectHandler);

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
        public FormDefService formDefService(FormDefMapper formDefMapper,
                                             FormConfigMapper formConfigMapper,
                                             FormSnapshotMapper formSnapshotMapper,
                                             DynamicTableManager dynamicTableManager,
                                             ObjectMapper objectMapper) {
            return new FormDefServiceImpl(formDefMapper, formConfigMapper, formSnapshotMapper,
                    dynamicTableManager, new FormIdGenerator(), objectMapper);
        }

        @Bean
        public FormFieldValidator formFieldValidator(FormConfigMapper formConfigMapper,
                                                     ObjectMapper objectMapper) {
            return new FormFieldValidator(formConfigMapper, objectMapper);
        }

        @Bean
        public FormSubmitService formSubmitService(FormDefMapper formDefMapper,
                                                  FormTraceMapper formTraceMapper,
                                                  DynamicTableManager dynamicTableManager,
                                                  ObjectMapper objectMapper,
                                                  JdbcTemplate jdbcTemplate,
                                                  FormFieldValidator formFieldValidator,
                                                  DomainEventPublisher eventPublisher) {
            return new FormSubmitService(formDefMapper, formTraceMapper,
                    dynamicTableManager, new FormIdGenerator(), objectMapper, jdbcTemplate,
                    noDictFacade(), eventPublisher,
                    Optional.empty(), formFieldValidator,
                    new ObjectProvider<FlowStartPort>() {
                        @Override public FlowStartPort getIfAvailable() { return null; }
                    });
        }

        @Bean
        public DomainEventPublisher domainEventPublisher(
                ApplicationEventPublisher delegate) {
            return new DomainEventPublisher(delegate);
        }

        private DictFacade noDictFacade() {
            return new DictFacade() {
                @Override
                public Optional<Boolean> isValidCode(String dictType, String code) {
                    return Optional.of(false);
                }

                @Override
                public Optional<List<DictItemDTO>> listByType(String dictType) {
                    return Optional.of(List.of());
                }

                @Override
                public Optional<Map<String, DictTypeDetailDTO>> findDictTypeDetails(
                        Collection<String> codes) {
                    return codes == null || codes.isEmpty()
                            ? Optional.empty() : Optional.of(Map.of());
                }

                @Override
                public Optional<String> inspectDictTypeForImport(String code) {
                    return code == null || code.isBlank()
                            ? Optional.empty() : Optional.of("MISSING");
                }

                @Override
                public Optional<Boolean> createDictTypeForImport(String code, String name,
                                                                           Integer status, String description,
                                                                           List<DictItemDTO> items) {
                    return Optional.of(false);
                }
            };
        }
    }
}
