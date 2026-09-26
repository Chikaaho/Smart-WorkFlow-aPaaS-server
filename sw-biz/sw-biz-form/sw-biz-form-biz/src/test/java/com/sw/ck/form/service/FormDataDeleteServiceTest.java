package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
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
 * 表单数据删除服务集成测试。
 *
 * <p>在 H2（PostgreSQL 模式）上验证删除链路：
 * <ul>
 *   <li>CASCADE 子表连带软删</li>
 *   <li>RESTRICT 命中禁删（有效引用存在）</li>
 *   <li>RESTRICT 放行（已软删引用不算）</li>
 *   <li>跨租户引用不参与判定</li>
 *   <li>跨租户删除被拦（影响 0 行）</li>
 *   <li>幂等：重复删除不报错</li>
 *   <li>无引用方表单：正常删除</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = FormDataDeleteServiceTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("表单数据删除服务·集成测试")
class FormDataDeleteServiceTest {

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private FormDataDeleteService formDataDeleteService;

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
    /** 记录已创建但尚未被主表记录引用的子表 ID，用于清理时单独 DROP */
    private final Map<String, String> subTableFieldToName = new HashMap<>();

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

        // 先清理子表（sw_form_table_* 可能有外键依赖）
        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE \"" + table + "\" CASCADE");
            } catch (Exception ignored) {}
        }
        createdTables.clear();
        subTableFieldToName.clear();

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

    /** 发布一个简单表单（不含 TABLE/REFERENCE），返回 (formKey, tableName, formId) */
    private TestFormSetup setupSimpleForm(String formKeySuffix) {
        String formKey = "del_test_" + formKeySuffix;
        FormDefDTO draft = formDefService.createDraft(formKey, "删除测试", "del_test", null);
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {
                    "title": "简单表单",
                    "fields": [
                        {"name": "title", "type": "TEXT", "required": true, "label": "标题"}
                    ]
                }
                """;
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());

        var entity = formDefMapper.selectById(draft.getId());
        String tableName = entity.getPhysicalTableName();
        assertThat(tableName).isNotNull();
        createdTables.add(tableName);
        collectSubTables(entity);

        return new TestFormSetup(formKey, tableName, draft.getId());
    }

    /** 发布一个含 TABLE 子表的表单，返回 (formKey, tableName, formId) */
    private TestFormSetup setupFormWithTable(String formKeySuffix) {
        String formKey = "del_tbl_" + formKeySuffix;
        FormDefDTO draft = formDefService.createDraft(formKey, "含子表表单", "del_tbl", null);
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {
                    "title": "含子表表单",
                    "fields": [
                        {"name": "title", "type": "TEXT", "required": true, "label": "标题"},
                        {"name": "items", "type": "TABLE", "required": false, "label": "明细", "subFields": [
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
        collectSubTables(entity);

        return new TestFormSetup(formKey, tableName, draft.getId());
    }

    /** 发布一个 REFERENCE 引用方表单（targetFormId 指向目标 formKey） */
    private TestFormSetup setupReferencingForm(String formKeySuffix, String targetFormKey) {
        String formKey = "del_ref_" + formKeySuffix;
        FormDefDTO draft = formDefService.createDraft(formKey, "引用方表单", "del_ref", null);
        createdFormIds.add(draft.getId());

        String definitionJson = """
                {
                    "title": "引用方表单",
                    "fields": [
                        {"name": "title", "type": "TEXT", "required": false, "label": "标题"},
                        {"name": "dept", "type": "REFERENCE", "targetFormId": "%s", "required": false, "label": "部门"}
                    ]
                }
                """.formatted(targetFormKey);
        formDefService.saveConfig(draft.getId(), definitionJson);
        formDefService.publish(draft.getId());

        var entity = formDefMapper.selectById(draft.getId());
        String tableName = entity.getPhysicalTableName();
        assertThat(tableName).isNotNull();
        createdTables.add(tableName);
        collectSubTables(entity);

        return new TestFormSetup(formKey, tableName, draft.getId());
    }

    /** 收集子表名加入清理列表 */
    private void collectSubTables(com.sw.ck.form.entity.FormDefEntity entity) {
        String subMappingJson = entity.getSubTableMapping();
        if (subMappingJson != null && !subMappingJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> mapping = objectMapper.readValue(subMappingJson, Map.class);
                for (Map.Entry<String, String> entry : mapping.entrySet()) {
                    createdTables.add(entry.getValue());
                    subTableFieldToName.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception ignored) {}
        }
    }

    /** 向指定表插入一条记录，返回 recordId */
    private String insertRecord(String tableName, String title, Long tenantId) {
        String recordId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?)",
                recordId, tenantId, TEST_USER_ID, TEST_USER_ID, title);
        return recordId;
    }

    /** 向引用方表插入一条含 REFERENCE 列值的记录 */
    private String insertReferencingRecord(String tableName, String title, String refColName, String refValue, Long tenantId) {
        String recordId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO \"" + tableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"title\", \"" + refColName + "\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?)",
                recordId, tenantId, TEST_USER_ID, TEST_USER_ID, title, refValue);
        return recordId;
    }

    /** 向子表插入记录 */
    private void insertSubRecord(String subTableName, String parentRecordId, String itemName, int qty, Long tenantId) {
        jdbcTemplate.update(
                "INSERT INTO \"" + subTableName + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", \"create_by\", \"update_time\", \"update_by\", \"version\", \"parent_record_id\", \"item_name\", \"qty\") "
                        + "VALUES (?, ?, 0, NOW(), ?, NOW(), ?, 0, ?, ?, ?)",
                UUID.randomUUID().toString(), tenantId, TEST_USER_ID, TEST_USER_ID, parentRecordId, itemName, qty);
    }

    private record TestFormSetup(String formKey, String tableName, String formId) {}

    // ==================== 测试 1：CASCADE 子表连带软删 ====================

    @Test
    @DisplayName("删主记录 → 子表行也被软删（deleted=1）")
    void delete_withTableChildren_shouldCascadeSoftDelete() {
        var setup = setupFormWithTable("cascade");
        String mainRecordId = insertRecord(setup.tableName, "主记录", TEST_TENANT_ID);

        String subTableName = subTableFieldToName.get("items");
        assertThat(subTableName).as("子表名应存在").isNotNull();
        insertSubRecord(subTableName, mainRecordId, "物品A", 3, TEST_TENANT_ID);
        insertSubRecord(subTableName, mainRecordId, "物品B", 5, TEST_TENANT_ID);
        // 另一条不相关子记录（不同的 parent_record_id）
        String otherParentId = UUID.randomUUID().toString();
        insertSubRecord(subTableName, otherParentId, "无关物品", 1, TEST_TENANT_ID);

        // —— 执行删除 ——
        formDataDeleteService.deleteRecord(setup.formKey, mainRecordId);

        // —— 验证主记录已软删 ——
        Integer mainDeleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + setup.tableName + "\" WHERE \"id\" = ?",
                Integer.class, mainRecordId);
        assertThat(mainDeleted).isEqualTo(1);

        // —— 验证子表关联行已软删 ——
        List<Map<String, Object>> childRows = jdbcTemplate.queryForList(
                "SELECT \"deleted\", \"item_name\" FROM \"" + subTableName + "\" WHERE \"parent_record_id\" = ?",
                mainRecordId);
        assertThat(childRows).hasSize(2);
        assertThat(childRows).allMatch(row -> (Integer) row.get("deleted") == 1);

        // —— 不相关子记录未被误删 ——
        Integer otherDeleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + subTableName + "\" WHERE \"parent_record_id\" = ?",
                Integer.class, otherParentId);
        assertThat(otherDeleted).isEqualTo(0);
    }

    // ==================== 测试 2：RESTRICT 命中禁删 ====================

    @Test
    @DisplayName("有 deleted=0 的引用方记录 → 抛 1505，主记录未动")
    void delete_withActiveReference_shouldThrowRestrictError() {
        // 创建被引用目标表单
        var targetSetup = setupSimpleForm("target");
        String targetRecordId = insertRecord(targetSetup.tableName, "目标记录", TEST_TENANT_ID);

        // 创建引用方表单（REFERENCE → 目标 formKey）
        var refSetup = setupReferencingForm("ref", targetSetup.formKey);
        insertReferencingRecord(refSetup.tableName, "引用记录", "ref_dept_id", targetRecordId, TEST_TENANT_ID);

        // —— 执行删除，预期抛 1505 ——
        assertThatThrownBy(() -> formDataDeleteService.deleteRecord(targetSetup.formKey, targetRecordId))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    BaseException be = (BaseException) e;
                    assertThat(be.getCode()).isEqualTo(FormErrorCode.DELETE_RESTRICT_REFERENCED.getCode());
                });

        // —— 验证主记录未被删除（事务回滚） ——
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + targetSetup.tableName + "\" WHERE \"id\" = ?",
                Integer.class, targetRecordId);
        assertThat(deleted).isEqualTo(0);
    }

    // ==================== 测试 3：RESTRICT 放行（已软删引用不算） ====================

    @Test
    @DisplayName("引用方记录已软删（deleted=1）→ 反查不命中 → 删除成功")
    void delete_withSoftDeletedReference_shouldSucceed() {
        var targetSetup = setupSimpleForm("tgt_soft");
        String targetRecordId = insertRecord(targetSetup.tableName, "目标记录", TEST_TENANT_ID);

        var refSetup = setupReferencingForm("ref_soft", targetSetup.formKey);
        insertReferencingRecord(refSetup.tableName, "已删引用", "ref_dept_id", targetRecordId, TEST_TENANT_ID);

        // 软删引用方记录
        jdbcTemplate.update(
                "UPDATE \"" + refSetup.tableName + "\" SET \"deleted\" = 1 WHERE \"ref_dept_id\" = ? AND \"tenant_id\" = ?",
                targetRecordId, TEST_TENANT_ID);

        // —— 执行删除，应成功 ——
        assertThatCode(() -> formDataDeleteService.deleteRecord(targetSetup.formKey, targetRecordId))
                .doesNotThrowAnyException();

        // —— 验证目标记录已软删 ——
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + targetSetup.tableName + "\" WHERE \"id\" = ?",
                Integer.class, targetRecordId);
        assertThat(deleted).isEqualTo(1);
    }

    // ==================== 测试 4：跨租户引用不参与判定 ====================

    @Test
    @DisplayName("他租户有引用方记录指向本记录 → 不算引用 → 本租户删除照常成功")
    void delete_crossTenantReference_shouldNotBlock() {
        var targetSetup = setupSimpleForm("tgt_cross");
        String targetRecordId = insertRecord(targetSetup.tableName, "目标记录", TEST_TENANT_ID);

        var refSetup = setupReferencingForm("ref_cross", targetSetup.formKey);
        // 引用方记录属于 OTHER_TENANT
        insertReferencingRecord(refSetup.tableName, "他租户引用", "ref_dept_id", targetRecordId, OTHER_TENANT_ID);

        // —— 执行删除（当前租户 TEST_TENANT），应成功 ——
        assertThatCode(() -> formDataDeleteService.deleteRecord(targetSetup.formKey, targetRecordId))
                .doesNotThrowAnyException();

        // —— 验证目标记录已软删 ——
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + targetSetup.tableName + "\" WHERE \"id\" = ?",
                Integer.class, targetRecordId);
        assertThat(deleted).isEqualTo(1);
    }

    // ==================== 测试 5：跨租户删除被拦 ====================

    @Test
    @DisplayName("删他租户记录 → tenant_id 不匹配 → 影响 0 行 → 幂等成功但数据未动")
    void delete_otherTenantRecord_shouldAffectZeroRows() {
        var setup = setupSimpleForm("other_tenant");
        // 属于 OTHER_TENANT 的记录
        String otherRecordId = insertRecord(setup.tableName, "他租户记录", OTHER_TENANT_ID);

        // —— 当前租户 TEST_TENANT 尝试删除 ——
        assertThatCode(() -> formDataDeleteService.deleteRecord(setup.formKey, otherRecordId))
                .doesNotThrowAnyException();

        // —— 验证他租户数据未被改动 ——
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + setup.tableName + "\" WHERE \"id\" = ?",
                Integer.class, otherRecordId);
        assertThat(deleted).isEqualTo(0);
    }

    // ==================== 测试 6：幂等 ====================

    @Test
    @DisplayName("重复删同一记录 → 第二次影响 0 行 → 不报错")
    void delete_idempotent_shouldNotThrow() {
        var setup = setupSimpleForm("idempotent");
        String recordId = insertRecord(setup.tableName, "幂等测试", TEST_TENANT_ID);

        // 第一次删除
        assertThatCode(() -> formDataDeleteService.deleteRecord(setup.formKey, recordId))
                .doesNotThrowAnyException();

        // 验证已软删
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + setup.tableName + "\" WHERE \"id\" = ?",
                Integer.class, recordId);
        assertThat(deleted).isEqualTo(1);

        // 第二次删除（幂等）
        assertThatCode(() -> formDataDeleteService.deleteRecord(setup.formKey, recordId))
                .doesNotThrowAnyException();
    }

    // ==================== 测试 7：无引用方表单 ====================

    @Test
    @DisplayName("没有任何表单 REFERENCE 指向本表单 → 反查空集 → 正常删除")
    void delete_noReferences_shouldSucceed() {
        var setup = setupSimpleForm("no_refs");
        String recordId = insertRecord(setup.tableName, "无引用记录", TEST_TENANT_ID);

        assertThatCode(() -> formDataDeleteService.deleteRecord(setup.formKey, recordId))
                .doesNotThrowAnyException();

        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT \"deleted\" FROM \"" + setup.tableName + "\" WHERE \"id\" = ?",
                Integer.class, recordId);
        assertThat(deleted).isEqualTo(1);
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
                    .url("jdbc:h2:mem:deletetest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
                @Override
                public Optional<Boolean> isValidCode(String dictType, String code) {
                    if (dictType == null || dictType.isBlank() || code == null || code.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(true);
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
                    dictFacade, eventPublisher, java.util.Optional.empty(), formFieldValidator,
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

        @Bean
        public FormDataDeleteService formDataDeleteService(FormDefService formDefService,
                                                            FormDefMapper formDefMapper,
                                                            JdbcTemplate jdbcTemplate,
                                                            ObjectMapper objectMapper) {
            return new FormDataDeleteService(formDefService, formDefMapper, jdbcTemplate, objectMapper);
        }
    }
}
