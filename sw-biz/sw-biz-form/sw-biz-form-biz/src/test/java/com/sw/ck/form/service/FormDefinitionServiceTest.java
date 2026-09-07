package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.*;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
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
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * 表单定义服务集成测试。
 * <p>
 * 在 H2（PostgreSQL 模式）上验证：
 * <ul>
 *   <li>草稿创建 → 只写元数据表，无物理表</li>
 *   <li>发布草稿 → 物理表建出 + 状态变更 + 快照</li>
 *   <li>已发布表单不可修改元数据</li>
 *   <li>非法字段名 → 白名单拦截</li>
 *   <li>渲染接口 → 返回 definition JSON</li>
 * </ul>
 * </p>
 *
 * <p>沿用前两步的隔离手法：手动创建 DataSource，不依赖 @EnableAutoConfiguration，
 * 元数据表手动建（不走 Flyway），动态宽表走 DynamicTableManager。</p>
 */
@SpringBootTest(classes = FormDefinitionServiceTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("表单定义服务·集成测试")
class FormDefinitionServiceTest {

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FormDefMapper formDefMapper;

    @Autowired
    private FormConfigMapper formConfigMapper;

    @Autowired
    private FormSnapshotMapper formSnapshotMapper;

    /** 测试中创建的动态宽表名，在 @AfterEach 中清理 */
    private final java.util.ArrayList<String> createdTables = new java.util.ArrayList<>();

    /** 测试中创建的 form ID，在 @AfterEach 中清理 */
    private final java.util.ArrayList<String> createdFormIds = new java.util.ArrayList<>();

    // ==================== 设置/清理 ====================

    @BeforeEach
    void setUp() {
        createMetadataTables();
    }

    @AfterEach
    void tearDown() {
        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE \"" + table + "\" CASCADE");
            } catch (Exception ignored) {
            }
        }
        createdTables.clear();

        for (String formId : createdFormIds) {
            try {
                jdbcTemplate.update("DELETE FROM sw_form_snapshot WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_config WHERE form_id = ?", formId);
                jdbcTemplate.update("DELETE FROM sw_form_def WHERE id = ?", formId);
            } catch (Exception ignored) {
            }
        }
        createdFormIds.clear();
    }

    // ==================== 测试 1：建草稿（只写元数据） ====================

    @Test
    @DisplayName("建草稿 → 只写 sw_form_def / sw_form_config，无物理表")
    void createDraft_shouldCreateOnlyMetadata() {
        // —— Act ——
        FormDefDTO dto = formDefService.createDraft("leave_request", "请假申请", "leave_apply", "员工请假申请单");
        createdFormIds.add(dto.getId());

        // —— Assert：元数据存在 ——
        FormDefEntity entity = formDefMapper.selectById(dto.getId());
        assertThat(entity).as("sw_form_def 记录应存在").isNotNull();
        assertThat(entity.getFormKey()).isEqualTo("leave_request");
        assertThat(entity.getStatus()).isEqualTo(FormStatusEnum.DRAFT.getCode());
        assertThat(entity.getFormVersion()).isEqualTo(1);
        assertThat(entity.getPhysicalTableName()).as("草稿态不应有物理表名").isNullOrEmpty();
        assertThat(entity.getLogicalTableName()).isEqualTo("leave_apply");

        // —— Assert：config 存在 ——
        LambdaQueryWrapper<FormConfigEntity> configQuery = Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, dto.getId());
        FormConfigEntity config = formConfigMapper.selectOne(configQuery);
        assertThat(config).as("sw_form_config 记录应存在").isNotNull();
        assertThat(config.getDefinition()).isEqualTo("{}");

        // —— Assert：无动态宽表（元数据表不算） ——
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'sw_form_%' AND table_name NOT IN ('sw_form_def','sw_form_config','sw_form_snapshot','sw_form_trace')",
                String.class);
        assertThat(tables).as("草稿态不应创建动态宽表").isEmpty();

        System.out.println("=== 草稿创建结果 ===");
        System.out.println("FormDef: id=" + entity.getId() + ", key=" + entity.getFormKey() + ", status=" + entity.getStatus());
        System.out.println("Config: definition=" + config.getDefinition());
    }

    // ==================== 测试 2：发布草稿 ====================

    @Test
    @DisplayName("发布草稿 → 物理表建出 + physical_table_name 回填 + status=PUBLISHED + snapshot 存一版")
    void publishDraft_shouldCreatePhysicalTable() {
        // —— Arrange：先建草稿，再保存 config（definition 是唯一字段真源） ——
        FormDefDTO draft = formDefService.createDraft("it_application", "IT申请", "it_request", "IT资源申请");
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {
                    "fields": [
                        {"name": "applicant_name", "type": "TEXT"},
                        {"name": "department", "type": "DICT", "dictType": "sys_dept"},
                        {"name": "budget", "type": "NUMBER"},
                        {"name": "start_date", "type": "DATE"},
                        {"name": "is_urgent", "type": "BOOL"},
                        {"name": "description", "type": "RICH_TEXT"}
                    ]
                }
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);

        // —— Act：发布（不再传 fieldSpecs，从 definition 派生） ——
        formDefService.publish(draft.getId());

        // —— Assert 1: 元数据更新 ——
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        assertThat(entity.getStatus()).isEqualTo(FormStatusEnum.PUBLISHED.getCode());
        assertThat(entity.getPhysicalTableName())
                .as("physical_table_name 应回填")
                .isNotNull()
                .matches("^sw_form_[a-z][a-z0-9]{9}$");
        assertThat(entity.getFormVersion()).isEqualTo(2); // 初始 1，发布+1
        createdTables.add(entity.getPhysicalTableName());

        // —— Assert 2: 物理表存在 + 列正确 ——
        String tableName = entity.getPhysicalTableName();
        assertThat(tableExists(tableName)).as("动态宽表应已创建").isTrue();
        assertThat(columnExists(tableName, "id")).isTrue();
        assertThat(columnExists(tableName, "applicant_name")).isTrue();
        assertThat(columnExists(tableName, "department")).isTrue();
        assertThat(columnExists(tableName, "is_urgent")).isTrue();
        // id 列类型：BIGINT → VARCHAR(36)
        assertThat(columnDataType(tableName, "id")).isIn("VARCHAR", "CHARACTER VARYING");
        // create_by 保持 BIGINT
        assertThat(columnDataType(tableName, "create_by")).isEqualTo("BIGINT");

        // —— Assert 3: 快照存在 ——
        LambdaQueryWrapper<FormSnapshotEntity> snapQuery = Wrappers.lambdaQuery(FormSnapshotEntity.class)
                .eq(FormSnapshotEntity::getFormId, draft.getId())
                .eq(FormSnapshotEntity::getFormVersion, 2);
        FormSnapshotEntity snapshot = formSnapshotMapper.selectOne(snapQuery);
        assertThat(snapshot).as("快照应存在").isNotNull();
        assertThat(snapshot.getDefinition()).isEqualTo(definitionJson);

        // —— 输出证据 ——
        System.out.println("=== 发布结果 ===");
        System.out.println("FormDef: id=" + entity.getId() + ", status=" + entity.getStatus()
                + ", physicalTable=" + entity.getPhysicalTableName() + ", version=" + entity.getFormVersion());
        System.out.println("=== 动态宽表 DDL ===");
        System.out.println(extractCreateTableDDL(tableName));
        System.out.println("=== Snapshot ===");
        System.out.println("version=" + snapshot.getFormVersion() + ", definition=" + snapshot.getDefinition());
    }

    // ==================== 测试 3：已发布表单不可改字段名 ====================

    @Test
    @DisplayName("对已发布表单调用 updateDraft → 报错拒绝")
    void updatePublishedForm_shouldReject() {
        // —— Arrange ——
        FormDefDTO draft = formDefService.createDraft("test_pub", "测试发布", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {"fields": [{"name": "field_a", "type": "TEXT"}]}
                """);
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());

        // —— Act & Assert ——
        assertThatThrownBy(() -> formDefService.updateDraft(draft.getId(), "newname", null, null))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.FORM_ALREADY_PUBLISHED.getCode());
                });

        System.out.println("=== 已发布表单修改元数据被拒绝 ===");
    }

    // ==================== 测试 4：非法字段名发布 → 白名单拦截 ====================

    @Test
    @DisplayName("字段名含非法值发布 → 白名单拦截、未建表")
    void publishWithInvalidColumnName_shouldReject() {
        // —— Arrange ——
        FormDefDTO draft = formDefService.createDraft("test_invalid", "测试非法", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {"fields": [{"name": "malicious; DROP TABLE", "type": "TEXT"}]}
                """);

        // —— Act & Assert ——
        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class);

        // 确认无动态宽表被创建
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        assertThat(entity.getStatus()).isEqualTo(FormStatusEnum.DRAFT.getCode());
        assertThat(entity.getPhysicalTableName()).isNullOrEmpty();

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'sw_form_%' AND table_name NOT IN ('sw_form_def','sw_form_config','sw_form_snapshot','sw_form_trace')",
                String.class);
        assertThat(tables).as("非法字段名应阻止建表").isEmpty();

        System.out.println("=== 非法字段名发布被拦截 ===");
    }

    // ==================== 测试 4b：24 列布局边界 ====================

    @Test
    @DisplayName("保存表单配置时非法 colSpan → 拒绝且不写入脏布局")
    void saveConfigWithInvalidColSpan_shouldReject() {
        FormDefDTO draft = formDefService.createDraft("test_invalid_col_span", "测试非法列宽", null, null);
        createdFormIds.add(draft.getId());

        assertThatThrownBy(() -> formDefService.saveConfig(draft.getId(), """
                {"fields": [{"name": "subject", "type": "TEXT", "colSpan": 25}]}
                """))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(FormErrorCode.DEFINITION_INVALID.getCode()));

        FormConfigEntity config = formConfigMapper.selectOne(Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, draft.getId()));
        assertThat(config.getDefinition()).isEqualTo("{}");
    }

    @Test
    @DisplayName("保存并发布合法 colSpan 1/12/24 → definition 与快照保持原值")
    void saveAndPublishWithValidColSpan_shouldPreserveDefinition() {
        FormDefDTO draft = formDefService.createDraft("test_valid_col_span", "测试合法列宽", null, null);
        createdFormIds.add(draft.getId());
        String definitionJson = """
                {"title":"列宽测试","fields":[
                  {"name":"one","type":"TEXT","colSpan":1},
                  {"name":"middle","type":"NUMBER","colSpan":12},
                  {"name":"full_width","type":"RICH_TEXT","colSpan":24}
                ]}
                """;

        formDefService.saveConfig(draft.getId(), definitionJson);
        assertThat(formDefService.getDefinitionById(draft.getId())).isEqualTo(definitionJson);

        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());
        FormSnapshotEntity snapshot = formSnapshotMapper.selectOne(Wrappers.lambdaQuery(FormSnapshotEntity.class)
                .eq(FormSnapshotEntity::getFormId, draft.getId())
                .eq(FormSnapshotEntity::getFormVersion, 2));
        assertThat(snapshot.getDefinition()).isEqualTo(definitionJson);
    }

    // ==================== 测试 5：重复字段名发布 → 拒绝 ====================

    @Test
    @DisplayName("字段名重复发布 → 报错拒绝、未建表")
    void publishWithDuplicateColumn_shouldReject() {
        FormDefDTO draft = formDefService.createDraft("test_dup", "测试重复", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {"fields": [
                    {"name": "same_field", "type": "TEXT"},
                    {"name": "same_field", "type": "NUMBER"}
                ]}
                """);

        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.DUPLICATE_COLUMN.getCode());
                });

        System.out.println("=== 重复字段名发布被拦截 ===");
    }

    // ==================== 测试 6：渲染接口 ====================

    @Test
    @DisplayName("渲染接口按 formKey / formId 取 definition → 内容正确")
    void getDefinition_shouldReturnConfig() {
        // —— Arrange ——
        FormDefDTO draft = formDefService.createDraft("render_test", "渲染测试", null, "测试用");
        createdFormIds.add(draft.getId());

        // 使用可发布的 definition（type 必须为合法 FieldType 大写）
        String definitionJson = """
                {"title": "测试表单", "fields": [{"name": "username", "type": "TEXT"}]}
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);

        // —— Act：按 ID 取 ——
        String defById = formDefService.getDefinitionById(draft.getId());
        assertThat(defById).isEqualTo(definitionJson);

        // —— Act：按 formKey 取 ——
        String defByKey = formDefService.getDefinition("render_test");
        assertThat(defByKey).isEqualTo(definitionJson);

        // —— Assert：DTO 查询 ——
        FormDefDTO queried = formDefService.getFormDef(draft.getId());
        assertThat(queried).isNotNull();
        assertThat(queried.getFormKey()).isEqualTo("render_test");
        assertThat(queried.getStatus()).isEqualTo(FormStatusEnum.DRAFT.getCode());

        // —— 也验证已发布表单的渲染 ——
        formDefService.publish(draft.getId());
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());

        String publishedDef = formDefService.getDefinitionById(draft.getId());
        assertThat(publishedDef).as("发布后渲染接口仍应返回 definition").isEqualTo(definitionJson);

        System.out.println("=== 渲染接口验证 ===");
        System.out.println("Definition by ID: " + defById);
        System.out.println("Definition by Key: " + defByKey);
    }

    @Test
    @DisplayName("发布含 TABLE 的表单后，按 ID / formKey 读取仍只返回主表 definition")
    void getDefinition_afterPublishingTable_shouldReadMainConfigOnly() throws Exception {
        FormDefDTO draft = formDefService.createDraft("render_table_test", "渲染子表测试", null, null);
        createdFormIds.add(draft.getId());
        String definitionJson = """
                {"title":"渲染子表测试","fields":[
                  {"name":"items","type":"TABLE","subFields":[{"name":"item_name","type":"TEXT"}]},
                  {"name":"remark","type":"TEXT"}
                ]}
                """;

        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());

        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        createdTables.add(entity.getPhysicalTableName());
        assertThat(entity.getSubTableMapping()).contains("items");
        Map<String, Object> mapping = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(entity.getSubTableMapping(), Map.class);
        createdTables.add(String.valueOf(mapping.get("items")));

        assertThat(formDefService.getDefinitionById(draft.getId())).isEqualTo(definitionJson);
        assertThat(formDefService.getDefinition("render_table_test")).isEqualTo(definitionJson);
    }

    // ==================== 测试 7：已有 formKey 重复 → 拒绝 ====================

    @Test
    @DisplayName("重复 formKey 创建草稿 → 报错")
    void createDraft_duplicateFormKey_shouldReject() {
        FormDefDTO dto = formDefService.createDraft("duplicate_key", "原始表单", null, null);
        createdFormIds.add(dto.getId());
        assertThatThrownBy(() -> formDefService.createDraft("duplicate_key", "重复表单", null, null))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.FORM_KEY_DUPLICATE.getCode());
                });
    }

    // ==================== 测试 8：disabled 类型发布 → 拒绝 ====================

    @Test
    @DisplayName("disabled 类型（EMAIL）发布 → 拒绝")
    void publishWithDisabledType_shouldReject() {
        FormDefDTO draft = formDefService.createDraft("test_disabled", "测试禁用类型", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {"fields": [{"name": "email", "type": "EMAIL"}]}
                """);

        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.FIELD_TYPE_DISABLED.getCode());
                });

        // 确认无动态宽表被创建
        FormDefEntity entity = formDefMapper.selectById(draft.getId());
        assertThat(entity.getStatus()).isEqualTo(FormStatusEnum.DRAFT.getCode());
    }

    // ==================== 测试 9：TABLE 套 TABLE 递归 → 拒绝 ====================

    @Test
    @DisplayName("subFields 内含 TABLE → 递归禁止拦截")
    void publishWithNestedTable_shouldReject() {
        FormDefDTO draft = formDefService.createDraft("test_nested", "测试嵌套TABLE", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {
                    "fields": [
                        {"name": "outer_table", "type": "TABLE", "subFields": [
                            {"name": "inner_table", "type": "TABLE", "subFields": [
                                {"name": "x", "type": "TEXT"}
                            ]}
                        ]}
                    ]
                }
                """);

        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.FIELD_NESTED_TABLE.getCode());
                    assertThat(be.getMessage()).contains("禁止递归");
                });

        System.out.println("=== 递归 TABLE 发布被拦截 ✓ ===");
    }

    // ==================== 测试 10：DICT 缺 dictType → 拒绝 ====================

    @Test
    @DisplayName("DICT 字段缺 dictType → 拒绝")
    void publishDictWithoutDictType_shouldReject() {
        FormDefDTO draft = formDefService.createDraft("test_nodt", "测试缺dictType", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {"fields": [{"name": "gender", "type": "DICT"}]}
                """);

        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.FIELD_ATTR_MISSING.getCode());
                });
    }

    // ==================== 测试 11：REFERENCE 缺 targetFormId → 拒绝 ====================

    @Test
    @DisplayName("REFERENCE 字段缺 targetFormId → 拒绝")
    void publishRefWithoutTarget_shouldReject() {
        FormDefDTO draft = formDefService.createDraft("test_notarget", "测试缺target", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {"fields": [{"name": "dept", "type": "REFERENCE"}]}
                """);

        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.FIELD_ATTR_MISSING.getCode());
                });
    }

    // ==================== 测试 12：TABLE 空 subFields → 拒绝 ====================

    @Test
    @DisplayName("TABLE 字段 subFields 为空 → 拒绝")
    void publishTableWithEmptySubFields_shouldReject() {
        FormDefDTO draft = formDefService.createDraft("test_emptysub", "测试空子字段", null, null);
        createdFormIds.add(draft.getId());
        formDefService.saveConfig(draft.getId(), """
                {"fields": [{"name": "items", "type": "TABLE", "subFields": []}]}
                """);

        assertThatThrownBy(() -> formDefService.publish(draft.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.FIELD_ATTR_MISSING.getCode());
                });
    }

    // ==================== 测试 13：分页查询表单定义列表 ====================

    @Test
    @DisplayName("分页查询 → 正确分页 + 排序 + keyword 过滤 + 只返元数据")
    void pageFormDefs_shouldReturnPaginatedMetadataOnly() {
        // —— Arrange：创建 5 个草稿，名称各异 ——
        for (int i = 1; i <= 5; i++) {
            FormDefDTO dto = formDefService.createDraft("page_test_" + i,
                    (i <= 2) ? "keyword_match_" + i : "other_form_" + i,
                    null, null);
            createdFormIds.add(dto.getId());
            // 制造 update_time 差异：交错睡几毫秒确保排序可验证
            sleepUninterruptedly(5);
        }

        // —— Test 1: 第一页 size=2 ——
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(2);
        PageResult<FormDefDTO> page1 = formDefService.pageFormDefs(pageParam, null);

        assertThat(page1.getTotal()).as("总记录数应为 5").isEqualTo(5);
        assertThat(page1.getRecords()).hasSize(2);
        assertThat(page1.getPageNum()).isEqualTo(1);
        assertThat(page1.getPageSize()).isEqualTo(2);
        // 验证排序：第 1 页第 1 条应是最后创建的 "other_form_5"（按 update_time 倒序）
        assertThat(page1.getRecords().get(0).getName()).isEqualTo("other_form_5");
        assertThat(page1.getRecords().get(1).getName()).isEqualTo("other_form_4");

        // —— Test 2: 第二页 ——
        pageParam.setPageNum(2);
        PageResult<FormDefDTO> page2 = formDefService.pageFormDefs(pageParam, null);
        assertThat(page2.getRecords()).hasSize(2);

        // —— Test 3: 只返元数据，不返 definition ——
        FormDefDTO first = page1.getRecords().get(0);
        assertThat(first.getId()).as("应返 ID").isNotNull();
        assertThat(first.getFormKey()).as("应返 formKey").isNotNull();
        assertThat(first.getName()).as("应返 name").isNotNull();
        assertThat(first.getStatus()).as("应返 status").isNotNull();
        assertThat(first.getCreateTime()).as("应返 createTime").isNotNull();
        assertThat(first.getUpdateTime()).as("应返 updateTime").isNotNull();

        // —— Test 4: keyword 过滤 ——
        PageResult<FormDefDTO> keywordResult = formDefService.pageFormDefs(pageParam, "keyword_match");
        assertThat(keywordResult.getTotal()).as("keyword 匹配数应为 2").isEqualTo(2);
        assertThat(keywordResult.getRecords()).allMatch(dto -> dto.getName().startsWith("keyword_match"));

        // —— Test 5: keyword 无匹配 ——
        PageResult<FormDefDTO> noMatch = formDefService.pageFormDefs(pageParam, "nonexistent");
        assertThat(noMatch.getTotal()).as("无匹配应返回 0").isEqualTo(0);
        assertThat(noMatch.getRecords()).isEmpty();

        // —— Test 6: keyword 为空字符串/空白 ——
        PageResult<FormDefDTO> emptyKeyword = formDefService.pageFormDefs(pageParam, "");
        assertThat(emptyKeyword.getTotal()).as("空 keyword 应返回全部").isEqualTo(5);
        PageResult<FormDefDTO> blankKeyword = formDefService.pageFormDefs(pageParam, "   ");
        assertThat(blankKeyword.getTotal()).as("空白 keyword 应返回全部").isEqualTo(5);

        System.out.println("=== 分页查询验证 ===");
        System.out.println("page1 total=" + page1.getTotal() + ", records=" + page1.getRecords().size()
                + ", first=" + page1.getRecords().get(0).getName());
        System.out.println("page2 total=" + page2.getTotal() + ", records=" + page2.getRecords().size());
        System.out.println("keyword match total=" + keywordResult.getTotal());
    }

    /** 毫秒级睡眠，辅助方法不做异常声明 */
    private static void sleepUninterruptedly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 测试辅助方法 ====================

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

        // 注意：H2 测试环境定义列用 CLOB 而非 JSON，避免 JDBC 驱动 JSON 类型返回值带额外引号的问题。
        // PostgreSQL 生产环境使用 JSONB（见 Flyway 脚本）。
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

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private String columnDataType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class, tableName, columnName);
    }

    private String extractCreateTableDDL(String tableName) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (\n");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, character_maximum_length, " +
                        "is_nullable, column_default " +
                        "FROM information_schema.columns " +
                        "WHERE table_name = ? " +
                        "ORDER BY ordinal_position",
                tableName);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            sb.append("  ").append(row.get("column_name"))
                    .append(" ").append(row.get("data_type"));
            if (row.get("character_maximum_length") != null) {
                sb.append("(").append(row.get("character_maximum_length")).append(")");
            }
            if ("NO".equals(row.get("is_nullable"))) {
                sb.append(" NOT NULL");
            }
            if (i < rows.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(")");
        return sb.toString();
    }

    // ==================== 测试上下文配置 ====================

    /**
     * 最小化测试上下文：手动创建所有 bean，不依赖 @EnableAutoConfiguration。
     * <p>
     * - H2（PostgreSQL 模式）DataSource
     * - MyBatis-Plus SqlSessionFactory（含乐观锁、逻辑删除、自定义 ID 生成器）
     * - DynamicTableManager
     * - FormDefService + 三个 Mapper
     * </p>
     */
    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:formservicetest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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

            // MyBatis-Plus 全局配置
            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);
            factory.setGlobalConfig(globalConfig);

            // MyBatis-Plus 插件
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
        public FormDefService formDefService(FormDefMapper formDefMapper,
                                              FormConfigMapper formConfigMapper,
                                              FormSnapshotMapper formSnapshotMapper,
                                              DynamicTableManager dynamicTableManager,
                                              ObjectMapper objectMapper) {
            return new FormDefServiceImpl(formDefMapper, formConfigMapper, formSnapshotMapper,
                    dynamicTableManager, new FormIdGenerator(), objectMapper);
        }
    }
}
