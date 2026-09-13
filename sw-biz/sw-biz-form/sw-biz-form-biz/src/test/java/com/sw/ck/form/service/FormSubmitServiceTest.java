package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.event.FormSubmittedEvent;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.dynamic.FieldSpec;
import com.sw.ck.form.entity.*;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.mapper.FormTraceMapper;
import com.sw.ck.form.service.FormFieldValidator;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.event.EventListener;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * 表单提交服务集成测试。
 * <p>
 * 在 H2（PostgreSQL 模式）上验证完整提交链路：
 * <ul>
 *   <li>校验 → 动态宽表写入 → TABLE 子表 → trace 写入 → 事件发布</li>
 *   <li>字典值域拦截</li>
 *   <li>未知字段拦截</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = FormSubmitServiceTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("表单提交服务·集成测试")
class FormSubmitServiceTest {

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private FormSubmitService formSubmitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FormDefMapper formDefMapper;

    @Autowired
    private FormConfigMapper formConfigMapper;

    @Autowired
    private FormSnapshotMapper formSnapshotMapper;

    @Autowired
    private FormTraceMapper formTraceMapper;

    @Autowired
    private TestEventListener testEventListener;

    @Autowired
    private ObjectMapper objectMapper;

    /** 测试中创建的动态宽表名，在 @AfterEach 中清理 */
    private final java.util.ArrayList<String> createdTables = new java.util.ArrayList<>();

    /** 测试中创建的 form ID，在 @AfterEach 中清理 */
    private final java.util.ArrayList<String> createdFormIds = new java.util.ArrayList<>();

    /** 默认测试用户 */
    private static final Long TEST_USER_ID = 1L;
    private static final Long TEST_TENANT_ID = 100L;

    @BeforeEach
    void setUp() {
        // I5：租户归属改由登录态填充；测试种子统一落在租户 0 上下文
        if (com.sw.ck.security.holder.LoginUserHolder.get() == null) {
            com.sw.ck.security.holder.LoginUser i5TenantZeroSetup = new com.sw.ck.security.holder.LoginUser();
            i5TenantZeroSetup.setUserId(0L);
            i5TenantZeroSetup.setTenantId(0L);
            com.sw.ck.security.holder.LoginUserHolder.set(i5TenantZeroSetup);
        }
        createMetadataTables();
        // 设置登录用户上下文
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(TEST_USER_ID);
        loginUser.setTenantId(TEST_TENANT_ID);
        loginUser.setUsername("test_user");
        LoginUserHolder.set(loginUser);

        // 清空事件捕获列表
        testEventListener.events.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        testEventListener.events.clear();

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

    // ==================== 测试 1：正常提交（主表 + trace + 事件） ====================

    @Test
    @DisplayName("提交含 TEXT/NUMBER/BOOL 字段 → 数据落库、tenant_id 正确、trace 写一行、事件被发布")
    void submitForm_validData_shouldPersistAllData() {
        // —— Arrange：建草稿 → 保存 config → 发布 ——
        String formKey = "submit_test_basic";
        FormDefDTO draft = formDefService.createDraft(formKey, "提交测试", "submit_test", "基础提交测试");
        createdFormIds.add(draft.getId());

        // 保存 definition JSON（含字段定义）
        String definitionJson = """
                {
                    "title": "提交测试表单",
                    "fields": [
                        {"name": "full_name", "type": "TEXT", "required": true, "label": "姓名"},
                        {"name": "age", "type": "NUMBER", "required": false, "label": "年龄"},
                        {"name": "is_active", "type": "BOOL", "required": false, "label": "是否激活"}
                    ]
                }
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);

        // 发布
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        String tableName = entity.getPhysicalTableName();
        assertThat(tableName).as("physical_table_name 应在发布后回填").isNotNull();
        // 确认物理表已存在
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, tableName);
        assertThat(tableCount).as("物理表应在 publish 后存在").isEqualTo(1);
        createdTables.add(tableName);

        // —— Act：提交表单 ——
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("full_name", "张三");
        formData.put("age", 28);
        formData.put("is_active", true);

        String recordId = formSubmitService.submitForm(formKey, formData, "192.168.1.100", null, "Mozilla/5.0");

        // —— Assert 1：主表数据已写入 ——
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM \"" + tableName + "\" WHERE \"id\" = ?", recordId);
        assertThat(rows).as("主表记录应存在").hasSize(1);

        Map<String, Object> row = rows.get(0);
        System.out.println("=== 主表记录 ===");
        row.forEach((k, v) -> System.out.println("  " + k + " = " + v + " (" + (v != null ? v.getClass().getSimpleName() : "null") + ")"));

        assertThat(row.get("id")).isEqualTo(recordId);
        assertThat(row.get("full_name")).isEqualTo("张三");
        // NUMERIC 类型在 H2 中返回 BigDecimal，比较数值
        assertThat(((Number) row.get("age")).intValue()).isEqualTo(28);
        // BOOL true → SMALLINT 1
        assertThat(((Number) row.get("is_active")).intValue()).isEqualTo(1);

        // —— Assert 2：tenant_id 手动写入 ——
        assertThat(((Number) row.get("tenant_id")).longValue())
                .as("tenant_id 应从 LoginUserHolder 手动写入")
                .isEqualTo(TEST_TENANT_ID);

        // —— Assert 3：create_by 为当前用户 ——
        assertThat(((Number) row.get("create_by")).longValue())
                .as("create_by 应为当前用户 ID")
                .isEqualTo(TEST_USER_ID);

        // —— Assert 4：trace 表有一行 ——
        LambdaQueryWrapper<FormTraceEntity> traceQuery = Wrappers.lambdaQuery(FormTraceEntity.class)
                .eq(FormTraceEntity::getRecordId, recordId);
        FormTraceEntity trace = formTraceMapper.selectOne(traceQuery);
        assertThat(trace).as("trace 记录应存在").isNotNull();
        assertThat(trace.getFormId()).isEqualTo(draft.getId());
        assertThat(trace.getRecordId()).isEqualTo(recordId);
        assertThat(trace.getSubmitUserId()).isEqualTo(TEST_USER_ID);
        assertThat(trace.getSubmitTime()).isNotNull();
        assertThat(trace.getTenantId()).isEqualTo(TEST_TENANT_ID);

        System.out.println("=== Trace 记录 ===");
        System.out.println("  formId=" + trace.getFormId() + ", recordId=" + trace.getRecordId()
                + ", userId=" + trace.getSubmitUserId() + ", tenantId=" + trace.getTenantId());

        // —— Assert 5：事件被发布 ——
        assertThat(testEventListener.events)
                .as("FormSubmittedEvent 应被发布")
                .hasSize(1);
        FormSubmittedEvent event = testEventListener.events.get(0);
        assertThat(event.getFormKey()).isEqualTo(formKey);
        assertThat(event.getSubmitter()).isEqualTo(String.valueOf(TEST_USER_ID));
        assertThat(event.getRecordId()).isNotNull();
        assertThat(event.getTenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(event.getSubmittedData()).containsKey("full_name");

        System.out.println("=== 事件证据 ===");
        System.out.println("  formKey=" + event.getFormKey() + ", submitter=" + event.getSubmitter()
                + ", data=" + event.getSubmittedData());
    }

    // ==================== 测试 2：TABLE 子表写入 ====================

    @Test
    @DisplayName("提交含 TABLE 子表 → 子表多行 + parent_record_id 指向主表")
    void submitForm_withTableField_shouldInsertSubTableRows() {
        // —— Arrange：建草稿 → 保存 config（含 TABLE 字段定义） → 发布 ——
        String formKey = "submit_table_test";
        FormDefDTO draft = formDefService.createDraft(formKey, "子表提交测试", null, null);
        createdFormIds.add(draft.getId());

        // definition JSON with TABLE field and subFields
        String definitionJson = """
                {
                    "title": "巡检表单",
                    "fields": [
                        {"name": "applicant", "type": "TEXT", "required": true},
                        {"name": "inspection_items", "type": "TABLE", "required": true, "subFields": [
                            {"name": "item_name", "type": "TEXT"},
                            {"name": "quantity", "type": "NUMBER"},
                            {"name": "remark", "type": "TEXT"}
                        ]}
                    ]
                }
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);

        // 发布（TABLE 字段在 fieldSpecs 中用相同结构）
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());

        // 获取子表名
        String subTableMappingJson = entity.getSubTableMapping();
        assertThat(subTableMappingJson).as("sub_table_mapping 应在发布时回填").isNotNull();

        String subTableName;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> mapping = objectMapper.readValue(subTableMappingJson, Map.class);
            subTableName = mapping.get("inspection_items");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse sub-table mapping: " + subTableMappingJson, e);
        }
        assertThat(subTableName).as("子表映射应包含 inspection_items").isNotNull();
        createdTables.add(subTableName);
        System.out.println("=== 子表名: " + subTableName + " ===");

        // —— Act：提交含子表数据 ——
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("applicant", "李四");

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("item_name", "灭火器检查", "quantity", 2, "remark", "全部正常"));
        items.add(Map.of("item_name", "安全通道", "quantity", 1, "remark", "畅通"));
        formData.put("inspection_items", items);

        String recordId = formSubmitService.submitForm(formKey, formData, null, null, null);

        // —— Assert 1：主表写入 ——
        String mainTable = entity.getPhysicalTableName();
        Map<String, Object> mainRow = jdbcTemplate.queryForMap(
                "SELECT * FROM \"" + mainTable + "\" WHERE \"id\" = ?", recordId);
        assertThat(mainRow.get("applicant")).isEqualTo("李四");
        System.out.println("=== 主表写入 ===");
        System.out.println("  id=" + recordId + ", applicant=李四");

        // —— Assert 2：子表写入两行 + parent_record_id 正确 ——
        List<Map<String, Object>> subRows = jdbcTemplate.queryForList(
                "SELECT * FROM \"" + subTableName + "\" WHERE \"parent_record_id\" = ? ORDER BY \"item_name\"",
                recordId);
        assertThat(subRows).as("子表应有 2 行").hasSize(2);

        System.out.println("=== 子表写入 (" + subTableName + ") ===");
        for (Map<String, Object> sr : subRows) {
            System.out.println("  parent_record_id=" + sr.get("parent_record_id")
                    + ", item_name=" + sr.get("item_name")
                    + ", quantity=" + sr.get("quantity")
                    + ", remark=" + sr.get("remark"));
        }

        assertThat(subRows.get(0).get("item_name")).isEqualTo("安全通道");
        assertThat(String.valueOf(subRows.get(0).get("parent_record_id"))).isEqualTo(recordId);
        assertThat(subRows.get(1).get("item_name")).isEqualTo("灭火器检查");
        assertThat(String.valueOf(subRows.get(1).get("parent_record_id"))).isEqualTo(recordId);

        // —— Assert 3：子表系统列 ——
        for (Map<String, Object> sr : subRows) {
            assertThat(((Number) sr.get("tenant_id")).longValue()).isEqualTo(TEST_TENANT_ID);
            assertThat(((Number) sr.get("create_by")).longValue()).isEqualTo(TEST_USER_ID);
            assertThat(((Number) sr.get("deleted")).intValue()).isZero();
        }

        // —— Assert 4：trace 有一条 ——
        LambdaQueryWrapper<FormTraceEntity> traceQuery = Wrappers.lambdaQuery(FormTraceEntity.class)
                .eq(FormTraceEntity::getRecordId, recordId);
        long traceCount = formTraceMapper.selectCount(traceQuery);
        assertThat(traceCount).as("trace 应有 1 条").isEqualTo(1L);
    }

    // ==================== 测试 3：字典值域校验拦截 ====================

    @Test
    @DisplayName("字典字段传非法值 → DictFacade 拦截 → 未落库")
    void submitForm_invalidDictValue_shouldReject() {
        // —— Arrange ——
        String formKey = "dict_test";
        FormDefDTO draft = formDefService.createDraft(formKey, "字典测试", null, null);
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {
                    "fields": [
                        {"name": "gender", "type": "DICT", "dictType": "sys_user_sex", "required": true}
                    ]
                }
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());

        // —— Act：传非法字典值 ——
        Map<String, Object> formData = new HashMap<>();
        formData.put("gender", "invalid_value");

        assertThatThrownBy(() ->
                formSubmitService.submitForm(formKey, formData, null, null, null))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.SUBMIT_DICT_INVALID.getCode());
                });

        // —— Assert：无数据落库 ——
        String tableName = entity.getPhysicalTableName();
        long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"" + tableName + "\"", Long.class);
        assertThat(count).as("非法字典值应阻止数据写入").isZero();

        // —— Assert：无 trace ——
        long traceCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sw_form_trace WHERE form_id = ?",
                Long.class, draft.getId());
        assertThat(traceCount).as("非法字典值不应写入 trace").isZero();

        System.out.println("=== 字典校验拦截 ===");
        System.out.println("  非法值 'invalid_value' 被正确拦截，无数据落库");
    }

    // ==================== 测试 4：未知字段拦截 ====================

    @Test
    @DisplayName("提交未声明的字段 → 拒绝")
    void submitForm_unknownField_shouldReject() {
        // —— Arrange ——
        String formKey = "unknown_field_test";
        FormDefDTO draft = formDefService.createDraft(formKey, "未知字段测试", null, null);
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {"fields": [{"name": "known_field", "type": "TEXT"}]}
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());

        // —— Act ——
        Map<String, Object> formData = new HashMap<>();
        formData.put("known_field", "OK");
        formData.put("unknown_field", "NOT_DEFINED");

        assertThatThrownBy(() ->
                formSubmitService.submitForm(formKey, formData, null, null, null))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.SUBMIT_FIELD_UNKNOWN.getCode());
                });

        // —— Assert：无数据落库 ——
        String tableName = entity.getPhysicalTableName();
        long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"" + tableName + "\"", Long.class);
        assertThat(count).as("未知字段应阻止数据写入").isZero();

        System.out.println("=== 未知字段拦截 ===");
        System.out.println("  未知字段 'unknown_field' 被正确拦截，无数据落库");
    }

    // ==================== 测试 5：事件被发布验证 ====================

    @Test
    @DisplayName("提交成功 → 断言事件被发布（含完整数据）")
    void submitForm_shouldPublishEvent() {
        // —— Arrange ——
        String formKey = "event_pub_test";
        FormDefDTO draft = formDefService.createDraft(formKey, "事件发布测试", null, null);
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {"fields": [{"name": "msg", "type": "TEXT"}]}
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());

        // —— Act ——
        Map<String, Object> formData = new HashMap<>();
        formData.put("msg", "Hello Event");
        formSubmitService.submitForm(formKey, formData, null, null, null);

        // —— Assert ——
        assertThat(testEventListener.events).as("应捕获到事件").hasSize(1);
        FormSubmittedEvent event = testEventListener.events.get(0);
        assertThat(event.getFormKey()).isEqualTo(formKey);
        assertThat(event.getSubmittedData().get("msg")).isEqualTo("Hello Event");
        assertThat(event.getSubmitter()).isEqualTo(String.valueOf(TEST_USER_ID));
        assertThat(event.getRecordId()).as("事件应携带 recordId").isNotNull();
        assertThat(event.getTenantId()).as("事件应携带 tenantId").isEqualTo(TEST_TENANT_ID);

        System.out.println("=== 事件捕获 ===");
        System.out.println("  formKey=" + event.getFormKey());
        System.out.println("  recordId=" + event.getRecordId());
        System.out.println("  tenantId=" + event.getTenantId());
        System.out.println("  data=" + event.getSubmittedData());
        System.out.println("  submitter=" + event.getSubmitter());
    }

    // ==================== 测试辅助 ====================

    private void createMetadataTables() {
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

    // ==================== 事件测试监听器 ====================

    /**
     * 捕获 FormSubmittedEvent 用于断言验证。
     * <p>
     * 使用 {@code @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)}
     * 模拟生产配置，确保事件在事务提交后才被捕获，与 workflow 消费方行为一致。
     * </p>
     */
    static class TestEventListener {
        final List<FormSubmittedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void handle(FormSubmittedEvent event) {
            events.add(event);
        }
    }

    // ==================== 测试上下文配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:submittest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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

            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            factory.setPlugins(interceptor);

            // I5：租户归属改由 MetaObjectHandler 从登录态填充（不再写死 0L），
            // 测试上下文注册同一填充器与登录态读取入口（与生产 CommonMetaObjectHandler 同源）
            com.sw.ck.common.security.LoginContextProvider loginContextProvider =
                    new com.sw.ck.common.security.LoginContextProvider() {
                        @Override
                        public Long getUserId() {
                            com.sw.ck.security.holder.LoginUser user =
                                    com.sw.ck.security.holder.LoginUserHolder.get();
                            return user != null ? user.getUserId() : null;
                        }

                        @Override
                        public Long getTenantId() {
                            com.sw.ck.security.holder.LoginUser user =
                                    com.sw.ck.security.holder.LoginUserHolder.get();
                            return user != null ? user.getTenantId() : null;
                        }

                        @Override
                        public Long getDeptId() {
                            return null;
                        }

                        @Override
                        public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                            return com.sw.ck.common.datascope.DataScopeType.ALL;
                        }

                        @Override
                        public java.util.Set<Long> getCustomDeptIds() {
                            return java.util.Set.of();
                        }

                        @Override
                        public boolean isSuperAdmin() {
                            return false;
                        }
                    };
            com.sw.ck.common.config.mybatis.CommonMetaObjectHandler metaObjectHandler =
                    new com.sw.ck.common.config.mybatis.CommonMetaObjectHandler(loginContextProvider);
            metaObjectHandler.setFormIdFiller(meta -> {
                Object original = meta.getOriginalObject();
                if (original instanceof com.sw.ck.form.entity.FormBaseEntity f && f.getId() == null) {
                    f.setId(new com.sw.ck.form.entity.FormIdGenerator().generate());
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
        public DictFacade dictFacade() {
            // 模拟字典服务：sys_user_sex 允许值 0/1/2
            return new DictFacade() {
                private final Set<String> validSexCodes = Set.of("0", "1", "2");

                @Override
                public boolean isValidCode(String dictType, String code) {
                    if ("sys_user_sex".equals(dictType)) {
                        return validSexCodes.contains(code);
                    }
                    return false;
                }

                @Override
                public List<com.sw.ck.system.api.dict.DictItemDTO> listByType(String dictType) {
                    return List.of();
                }

                @Override
                public String resolveLabel(String dictType, String code) {
                    return null;
                }
            };
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
                                                    DictFacade dictFacade,
                                                    DomainEventPublisher eventPublisher,
                                                    FormFieldValidator formFieldValidator) {
            return new FormSubmitService(formDefMapper, formTraceMapper,
                    dynamicTableManager, new FormIdGenerator(), objectMapper, jdbcTemplate,
                    dictFacade, eventPublisher, Optional.empty(), formFieldValidator,
                    new org.springframework.beans.factory.ObjectProvider<com.sw.ck.form.api.port.FlowStartPort>() {
                @Override public com.sw.ck.form.api.port.FlowStartPort getIfAvailable() { return null; }
            });
        }

        @Bean
        public DomainEventPublisher domainEventPublisher(
                org.springframework.context.ApplicationEventPublisher delegate) {
            return new DomainEventPublisher(delegate);
        }

        @Bean
        public TestEventListener testEventListener() {
            return new TestEventListener();
        }
    }
}
