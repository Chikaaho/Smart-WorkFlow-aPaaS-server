package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FilterOp;
import com.sw.ck.form.api.dto.FormDataFilter;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.FormIdGenerator;
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
import org.junit.jupiter.api.Nested;
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

import javax.sql.DataSource;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * 表单数据查询服务集成测试。
 *
 * <p>在 H2（PostgreSQL 模式）上验证查询链路：
 * <ul>
 *   <li>正常分页查询</li>
 *   <li>各 op×type 合法路径</li>
 *   <li>非法组合拒绝</li>
 *   <li>未知字段拒绝</li>
 *   <li>deleted 过滤生效</li>
 *   <li>租户过滤生效</li>
 *   <li>分页 size 钳制</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = FormDataQueryServiceTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("表单数据查询服务·集成测试")
class FormDataQueryServiceTest {

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private FormDataQueryService formDataQueryService;

    @Autowired
    private FormSubmitService formSubmitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FormDefMapper formDefMapper;

    @Autowired
    private FormConfigMapper formConfigMapper;

    @Autowired
    private FormTraceMapper formTraceMapper;

    @Autowired
    private FormSnapshotMapper formSnapshotMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> createdTables = new ArrayList<>();
    private final List<String> createdFormIds = new ArrayList<>();

    private static final Long TEST_USER_ID = 1L;
    private static final Long TEST_TENANT_ID = 100L;
    private static final Long OTHER_TENANT_ID = 999L;

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

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(TEST_USER_ID);
        loginUser.setTenantId(TEST_TENANT_ID);
        loginUser.setUsername("test_user");
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();

        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE \"" + table + "\" CASCADE");
            } catch (Exception ignored) {}
        }
        createdTables.clear();

        for (String formId : createdFormIds) {
            try {
                jdbcTemplate.update("DELETE FROM sw_form_trace WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_snapshot WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_config WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_def WHERE id = ?", formId);
            } catch (Exception ignored) {}
        }
        createdFormIds.clear();
    }

    // ==================== 辅助方法 ====================

    /** 发布一个含多种字段类型的测试表单，插入若干记录，返回 (formKey, tableName) */
    private TestFormSetup setupQueryForm(String formKeySuffix) {
        String formKey = "query_test_" + formKeySuffix;
        FormDefDTO draft = formDefService.createDraft(formKey, "查询测试", "query_test", null);
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {
                    "title": "查询测试表单",
                    "fields": [
                        {"name": "title", "type": "TEXT", "required": true, "label": "标题"},
                        {"name": "score", "type": "NUMBER", "required": false, "label": "分数"},
                        {"name": "apply_date", "type": "DATE", "required": false, "label": "申请日期"},
                        {"name": "is_approved", "type": "BOOL", "required": false, "label": "是否审批"},
                        {"name": "gender", "type": "DICT", "dictType": "sys_user_sex", "required": false, "label": "性别"},
                        {"name": "dept", "type": "REFERENCE", "targetFormId": "dept_form", "required": false, "label": "部门"},
                        {"name": "content", "type": "RICH_TEXT", "required": false, "label": "内容"},
                        {"name": "details", "type": "TABLE", "required": false, "label": "明细", "subFields": [
                            {"name": "item_name", "type": "TEXT"},
                            {"name": "qty", "type": "NUMBER"}
                        ]}
                    ]
                }
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());

        var entity = formDefMapper.selectById(draft.getId());
        String tableName = entity.getPhysicalTableName();
        assertThat(tableName).isNotNull();
        createdTables.add(tableName);

        // 递归收集子表名（sub_table_mapping JSON）并加入 createdTables
        String subMappingJson = entity.getSubTableMapping();
        if (subMappingJson != null && !subMappingJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> mapping = objectMapper.readValue(subMappingJson, Map.class);
                for (String subTable : mapping.values()) {
                    createdTables.add(subTable);
                }
            } catch (Exception ignored) {}
        }

        return new TestFormSetup(formKey, tableName);
    }

    private record TestFormSetup(String formKey, String tableName) {}

    // ==================== 测试 1：正常分页查询 ====================

    @Test
    @DisplayName("空查询 → 返回分页结果，包含系统列和用户列")
    void query_empty_shouldReturnPagedResults() {
        var setup = setupQueryForm("basic");
        String tableName = setup.tableName;

        // Insert 3 records using raw SQL
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update(
                    "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"score\", \"is_approved\") "
                            + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?, ?)",
                    UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID,
                    "Title " + i, i * 10, i % 2 == 0 ? 1 : 0);
        }

        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(3);
        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getPageNum()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(10);

        // 验证列投影含（投影系统列 + 用户列）
        Map<String, Object> firstRow = result.getRecords().get(0);
        assertThat(firstRow).containsKeys("id", "create_time", "create_by", "update_time", "update_by");
        assertThat(firstRow).doesNotContainKeys("tenant_id", "deleted", "version");
        assertThat(firstRow).containsKey("title");
        assertThat(firstRow).containsKey("score");
        assertThat(firstRow).containsKey("is_approved");
        // RICH_TEXT 不可筛选但仍可投影
        assertThat(firstRow).containsKey("content");
        // TABLE 不产生列 → 不投影
        assertThat(firstRow).doesNotContainKey("details");
        // REFERENCE → ref_{name}_id
        assertThat(firstRow).containsKey("ref_dept_id");
    }

    // ==================== 测试 2：分页裁剪 ====================

    @Test
    @DisplayName("分页 → 第二页只返回 1 条")
    void query_pagination_shouldReturnCorrectPage() {
        var setup = setupQueryForm("page");
        String tableName = setup.tableName;

        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.update(
                    "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                            + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                    UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID,
                    "Record " + i);
        }

        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setPageNum(2);
        request.setPageSize(2);

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(result.getPageNum()).isEqualTo(2);
    }

    // ==================== 测试 3：EQ 过滤 (TEXT) ====================

    @Test
    @DisplayName("TEXT EQ → 只返回匹配行")
    void filter_textEq_shouldReturnMatchingRows() {
        var setup = setupQueryForm("text_eq");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Alpha");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Beta");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Alpha");

        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);
        FormDataFilter filter = new FormDataFilter();
        filter.setField("title");
        filter.setOp(FilterOp.EQ);
        filter.setValue("Alpha");
        request.setFilters(List.of(filter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords().get(0).get("title")).isEqualTo("Alpha");
    }

    // ==================== 测试 3b：系统主键 id 过滤（引用显示名解析依赖） ====================

    @Test
    @DisplayName("系统列 id EQ → 精确返回该行；id 非 EQ → 拒绝")
    void filter_systemIdEq_shouldReturnSingleRow_andNonEqRejected() {
        var setup = setupQueryForm("id_eq");
        String tableName = setup.tableName;
        String targetId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                targetId, TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Target");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Other");

        // 正向：id EQ 精确返回该行
        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);
        FormDataFilter eqFilter = new FormDataFilter();
        eqFilter.setField("id");
        eqFilter.setOp(FilterOp.EQ);
        eqFilter.setValue(targetId);
        request.setFilters(List.of(eqFilter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords().get(0).get("id")).isEqualTo(targetId);
        assertThat(result.getRecords().get(0).get("title")).isEqualTo("Target");

        // 反向：id LIKE 拒绝（系统列仅放行 EQ）
        FormDataQueryRequest likeRequest = new FormDataQueryRequest();
        likeRequest.setPageNum(1);
        likeRequest.setPageSize(10);
        FormDataFilter likeFilter = new FormDataFilter();
        likeFilter.setField("id");
        likeFilter.setOp(FilterOp.LIKE);
        likeFilter.setValue(targetId);
        likeRequest.setFilters(List.of(likeFilter));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> formDataQueryService.queryFormData(setup.formKey, likeRequest))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("仅支持 EQ");
    }

    // ==================== 测试 4：LIKE 过滤 (TEXT) ====================

    @Test
    @DisplayName("TEXT LIKE → 模糊匹配 + 转义")
    void filter_textLike_shouldReturnMatchingRows() {
        var setup = setupQueryForm("text_like");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Hello World");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Foo Bar");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Hello Universe");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("title");
        filter.setOp(FilterOp.LIKE);
        filter.setValue("Hello");
        request.setFilters(List.of(filter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    // ==================== 测试 5：NUMBER GE / LE ====================

    @Test
    @DisplayName("NUMBER GE + LE → 范围查询")
    void filter_numberRange_shouldReturnMatchingRows() {
        var setup = setupQueryForm("num_range");
        String tableName = setup.tableName;

        for (int score : new int[]{10, 50, 90}) {
            jdbcTemplate.update(
                    "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"score\") "
                            + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                    UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID,
                    "Item " + score, score);
        }

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter geFilter = new FormDataFilter();
        geFilter.setField("score");
        geFilter.setOp(FilterOp.GE);
        geFilter.setValue(30);

        FormDataFilter leFilter = new FormDataFilter();
        leFilter.setField("score");
        leFilter.setOp(FilterOp.LE);
        leFilter.setValue(80);

        request.setFilters(List.of(geFilter, leFilter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(((Number) result.getRecords().get(0).get("score")).intValue()).isEqualTo(50);
    }

    // ==================== 测试 6：BOOL EQ ====================

    @Test
    @DisplayName("BOOL EQ → true/false 转为 1/0 比较")
    void filter_boolEq_shouldReturnMatchingRows() {
        var setup = setupQueryForm("bool_eq");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"is_approved\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "A", 1);
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"is_approved\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "B", 0);

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("is_approved");
        filter.setOp(FilterOp.EQ);
        filter.setValue(true); // Boolean true → 1

        request.setFilters(List.of(filter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).get("title")).isEqualTo("A");
    }

    // ==================== 测试 7：DICT EQ ====================

    @Test
    @DisplayName("DICT EQ → 按字典 code 值精确匹配")
    void filter_dictEq_shouldReturnMatchingRows() {
        var setup = setupQueryForm("dict_eq");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"gender\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "M", "1");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"gender\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "F", "2");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("gender");
        filter.setOp(FilterOp.EQ);
        filter.setValue("1");

        request.setFilters(List.of(filter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).get("gender")).isEqualTo("1");
    }

    // ==================== 测试 8：REFERENCE EQ ====================

    @Test
    @DisplayName("REFERENCE EQ → 按 ref_{name}_id 列匹配")
    void filter_referenceEq_shouldReturnMatchingRows() {
        var setup = setupQueryForm("ref_eq");
        String tableName = setup.tableName;

        String refId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"ref_dept_id\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "WithRef", refId);
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "NoRef");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("dept");
        filter.setOp(FilterOp.EQ);
        filter.setValue(refId);

        request.setFilters(List.of(filter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).get("ref_dept_id")).isEqualTo(refId);
    }

    // ==================== 测试 9：DATE GE / LE ====================

    @Test
    @DisplayName("DATE GE → 日期范围过滤")
    void filter_dateRange_shouldReturnMatchingRows() {
        var setup = setupQueryForm("date_range");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"apply_date\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Early", "2025-01-01 00:00:00");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"apply_date\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Mid", "2025-06-15 00:00:00");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"apply_date\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Late", "2025-12-31 00:00:00");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter geFilter = new FormDataFilter();
        geFilter.setField("apply_date");
        geFilter.setOp(FilterOp.GE);
        geFilter.setValue("2025-03-01 00:00:00");

        FormDataFilter leFilter = new FormDataFilter();
        leFilter.setField("apply_date");
        leFilter.setOp(FilterOp.LE);
        leFilter.setValue("2025-09-30 00:00:00");

        request.setFilters(List.of(geFilter, leFilter));

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).get("title")).isEqualTo("Mid");
    }

    // ==================== 测试 10：非法组合 - RICH_TEXT 不可筛选 ====================

    @Test
    @DisplayName("RICH_TEXT EQ → 拒，抛 QUERY_FILTER_FIELD_NOT_FILTERABLE 且消息用显示名（R3a）")
    void filter_richText_shouldReject() {
        var setup = setupQueryForm("rich_text");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        // 字段可查看（非 viewDenied），拒绝消息因此必须用 definition 的 label 而非字段键
        filter.setField("content");
        filter.setOp(FilterOp.EQ);
        filter.setValue("test");
        request.setFilters(List.of(filter));

        assertThatThrownBy(() -> formDataQueryService.queryFormData(setup.formKey, request))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE.getCode());
                    assertThat(be.getMessage()).contains("内容").doesNotContain("content");
                });
    }

    // ==================== 测试 11：非法组合 - TABLE 不可筛选 ====================

    @Test
    @DisplayName("TABLE EQ → 拒，抛 QUERY_FILTER_FIELD_NOT_FILTERABLE")
    void filter_tableField_shouldReject() {
        var setup = setupQueryForm("table_reject");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("details");
        filter.setOp(FilterOp.EQ);
        filter.setValue("test");
        request.setFilters(List.of(filter));

        assertThatThrownBy(() -> formDataQueryService.queryFormData(setup.formKey, request))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE.getCode());
                });
    }

    // ==================== 测试 12：非法组合 - BOOL LIKE ====================

    @Test
    @DisplayName("BOOL LIKE → 拒，抛 QUERY_FILTER_OP_TYPE_MISMATCH")
    void filter_boolLike_shouldReject() {
        var setup = setupQueryForm("bool_like");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("is_approved");
        filter.setOp(FilterOp.LIKE);
        filter.setValue("true");
        request.setFilters(List.of(filter));

        assertThatThrownBy(() -> formDataQueryService.queryFormData(setup.formKey, request))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH.getCode());
                });
    }

    // ==================== 测试 13：未知字段拒绝 ====================

    @Test
    @DisplayName("过滤字段不在 definition → 拒，抛 QUERY_FILTER_FIELD_UNKNOWN")
    void filter_unknownField_shouldReject() {
        var setup = setupQueryForm("unknown");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("nonexistent");
        filter.setOp(FilterOp.EQ);
        filter.setValue("test");
        request.setFilters(List.of(filter));

        assertThatThrownBy(() -> formDataQueryService.queryFormData(setup.formKey, request))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.QUERY_FILTER_FIELD_UNKNOWN.getCode());
                });
    }

    // ==================== 测试 14：deleted 过滤生效 ====================

    @Test
    @DisplayName("deleted=1 的记录不被返回")
    void query_deletedRecords_shouldBeExcluded() {
        var setup = setupQueryForm("deleted");
        String tableName = setup.tableName;

        // Insert 1 normal + 1 soft-deleted
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Visible");
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 1, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Deleted");

        FormDataQueryRequest request = new FormDataQueryRequest();

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords().get(0).get("title")).isEqualTo("Visible");
    }

    // ==================== 测试 15：租户过滤生效 ====================

    @Test
    @DisplayName("其他租户的记录不被返回（租户隔离）")
    void query_otherTenantRecords_shouldBeExcluded() {
        var setup = setupQueryForm("tenant");
        String tableName = setup.tableName;

        // Insert for current tenant
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Mine");
        // Insert for other tenant
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), OTHER_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Other");

        FormDataQueryRequest request = new FormDataQueryRequest();

        // Current user is TEST_TENANT_ID
        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords().get(0).get("title")).isEqualTo("Mine");
        // WHERE 过滤（tenant_id=?）已保证其他租户数据不被返回
        // 注意：tenant_id 已从 SELECT 投影中剔除，不再校验该列的返回值
    }

    // ==================== 测试 16：size 上限钳制 ====================

    @Test
    @DisplayName("size 超过 200 → 钳到 200，不报错")
    void query_sizeExceedsMax_shouldClamp() {
        var setup = setupQueryForm("size_clamp");
        String tableName = setup.tableName;

        // Insert 5 records
        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.update(
                    "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                            + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                    UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "R" + i);
        }

        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setPageSize(999); // 超过上限

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getPageSize()).isEqualTo(200); // 钳到上限
        assertThat(result.getRecords()).hasSize(5); // 实际只有 5 条
    }

    // ==================== 测试 17：空页（超过总数） ====================

    @Test
    @DisplayName("页码超过总数 → 返回空列表")
    void query_pageBeyondTotal_shouldReturnEmptyList() {
        var setup = setupQueryForm("empty_page");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "Only");

        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setPageNum(999);

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(1);
    }

    // ==================== 测试 18：OP IN v1 不支持 ====================

    @Test
    @DisplayName("op=IN → 拒，抛 QUERY_FILTER_OP_NOT_SUPPORTED")
    void filter_inOp_shouldReject() {
        var setup = setupQueryForm("in_reject");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("title");
        filter.setOp(FilterOp.IN);
        filter.setValue(List.of("A", "B"));
        request.setFilters(List.of(filter));

        assertThatThrownBy(() -> formDataQueryService.queryFormData(setup.formKey, request))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.QUERY_FILTER_OP_NOT_SUPPORTED.getCode());
                });
    }

    // ==================== 测试 19：表单不存在 ====================

    @Test
    @DisplayName("formKey 不存在 → 抛 QUERY_FORM_NOT_EXIST")
    void query_nonexistentForm_shouldReject() {
        FormDataQueryRequest request = new FormDataQueryRequest();

        assertThatThrownBy(() -> formDataQueryService.queryFormData("nonexistent_form_key", request))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.QUERY_FORM_NOT_EXIST.getCode());
                });
    }

    // ==================== 测试 20：无过滤条件（null filters） ====================

    @Test
    @DisplayName("filters 为 null → 正常分页，不报错")
    void query_nullFilters_shouldReturnAll() {
        var setup = setupQueryForm("null_filters");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "X");

        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setFilters(null);

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    // ==================== 测试 21：page < 1 钳到 1 ====================

    @Test
    @DisplayName("page < 1 → 钳到 1")
    void query_pageLessThanOne_shouldClamp() {
        var setup = setupQueryForm("page_zero");
        String tableName = setup.tableName;

        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                UUID.randomUUID().toString(), TEST_TENANT_ID, TEST_USER_ID, TEST_USER_ID, "P");

        FormDataQueryRequest request = new FormDataQueryRequest();
        request.setPageNum(0);

        PageResult<Map<String, Object>> result = formDataQueryService.queryFormData(setup.formKey, request);

        assertThat(result.getPageNum()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);
    }

    // ==================== 测试 22：NUMBER LIKE 应拒绝 ====================

    @Test
    @DisplayName("NUMBER LIKE → 拒，抛 QUERY_FILTER_OP_TYPE_MISMATCH")
    void filter_numberLike_shouldReject() {
        var setup = setupQueryForm("num_like");

        FormDataQueryRequest request = new FormDataQueryRequest();
        FormDataFilter filter = new FormDataFilter();
        filter.setField("score");
        filter.setOp(FilterOp.LIKE);
        filter.setValue("10");
        request.setFilters(List.of(filter));

        assertThatThrownBy(() -> formDataQueryService.queryFormData(setup.formKey, request))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH.getCode());
                });
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
                    id                 VARCHAR(36)  PRIMARY KEY,
                    form_id            VARCHAR(36)  NOT NULL,
                    record_id          VARCHAR(36)  NOT NULL,
                    submit_user_id     BIGINT       NOT NULL,
                    submit_ip          VARCHAR(200),
                    submit_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    device_fingerprint VARCHAR(200),
                    user_agent         VARCHAR(500),
                    tenant_id          BIGINT       NOT NULL DEFAULT 0,
                    deleted            SMALLINT     NOT NULL DEFAULT 0,
                    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by          BIGINT,
                    update_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by          BIGINT,
                    version            BIGINT       NOT NULL DEFAULT 0
                )
                """);
    }

    // ==================== 测试上下文配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:querytest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
            return new DictFacade() {
                private final Set<String> validSexCodes = Set.of("0", "1", "2");

                @Override
                public Optional<Boolean> isValidCode(String dictType, String code) {
                    if (dictType == null || dictType.isBlank() || code == null || code.isBlank()) {
                        return Optional.empty();
                    }
                    if ("sys_user_sex".equals(dictType)) {
                        return Optional.of(validSexCodes.contains(code));
                    }
                    return Optional.of(false);
                }

                @Override
                public Optional<List<com.sw.ck.system.api.dict.DictItemDTO>> listByType(String dictType) {
                    if (dictType == null || dictType.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(List.of());
                }
            };
        }

        @Bean
        public DomainEventPublisher domainEventPublisher(
                org.springframework.context.ApplicationEventPublisher delegate) {
            return new DomainEventPublisher(delegate);
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
        public FormDataQueryService formDataQueryService(FormDefService formDefService,
                                                          FormDefMapper formDefMapper,
                                                          FormConfigMapper formConfigMapper,
                                                          JdbcTemplate jdbcTemplate,
                                                          ObjectMapper objectMapper) {
            return new FormDataQueryService(formDefService, formDefMapper, formConfigMapper, jdbcTemplate, objectMapper);
        }
    }
}
