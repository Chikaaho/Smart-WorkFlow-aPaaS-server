package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.dto.FormSnapshotDTO;
import com.sw.ck.form.api.dto.FormSnapshotDetailDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormSnapshotEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
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
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 表单历史版本快照查询集成测试。
 * <p>
 * 在 H2（PostgreSQL 模式）上验证快照只读查询契约：
 * <ul>
 *   <li>发布产生快照后，列表按版本号倒序返回且不含 definition</li>
 *   <li>按版本读取快照详情返回完整 definition（与发布时 config 一致）</li>
 *   <li>表单不存在 → 1000；版本不存在 → 1301</li>
 *   <li>草稿态（未发布过）→ 空列表，不报错</li>
 *   <li>逻辑删除的快照不进列表、不可读取</li>
 * </ul>
 * </p>
 *
 * <p>装配手法沿用 {@code FormDefinitionServiceTest}：手动 DataSource + 手动建元数据表，
 * 不依赖 @EnableAutoConfiguration 与 Flyway。</p>
 */
@SpringBootTest(classes = FormSnapshotQueryTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("表单历史版本快照查询·集成测试")
class FormSnapshotQueryTest {

    @Autowired
    private FormDefService formDefService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试中创建的 form ID，在 @AfterEach 中清理 */
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
        createMetadataTables();
    }

    @AfterEach
    void tearDown() {
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

    // ==================== 快照列表 ====================

    @Nested
    @DisplayName("快照列表")
    class ListSnapshots {

        @Test
        @DisplayName("多次发布 → 列表按版本号倒序，行内不含 definition")
        void listSnapshots_orderedDesc_withoutDefinition() {
            String formId = createDraftWithDefinition("snapshot_list_case",
                    "{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\"}]}");

            // 模拟两次发布产生的两版快照（绕开真实 DDL：直接插快照行，
            // 与 publish Step 6 写入的行形状一致）
            insertSnapshot(formId, 1, "{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\"}]}");
            insertSnapshot(formId, 2, "{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\"},{\"name\":\"days\",\"type\":\"NUMBER\"}]}");

            List<FormSnapshotDTO> snapshots = formDefService.listSnapshots(formId);

            assertThat(snapshots).as("应返回两版快照").hasSize(2);
            assertThat(snapshots.get(0).getFormVersion()).as("版本号倒序").isEqualTo(2);
            assertThat(snapshots.get(1).getFormVersion()).isEqualTo(1);
            assertThat(snapshots.get(0).getCreateTime()).as("应带快照产生时间").isNotNull();
        }

        @Test
        @DisplayName("草稿态（从未发布）→ 空列表，不报错")
        void listSnapshots_draftForm_emptyList() {
            String formId = createDraftWithDefinition("snapshot_draft_case", "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"}]}");

            assertThat(formDefService.listSnapshots(formId)).isEmpty();
        }

        @Test
        @DisplayName("表单不存在 → FORM_NOT_FOUND(1000)")
        void listSnapshots_formNotFound() {
            assertThatThrownBy(() -> formDefService.listSnapshots("no-such-form"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining(FormErrorCode.FORM_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("逻辑删除的快照不进列表")
        void listSnapshots_excludesLogicDeleted() {
            String formId = createDraftWithDefinition("snapshot_deleted_case", "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"}]}");
            insertSnapshot(formId, 1, "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"}]}");
            insertSnapshot(formId, 2, "{\"fields\":[{\"name\":\"b\",\"type\":\"TEXT\"}]}");
            jdbcTemplate.update("UPDATE sw_form_snapshot SET deleted = 1 WHERE form_id = ? AND form_version = 2", formId);

            List<FormSnapshotDTO> snapshots = formDefService.listSnapshots(formId);

            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.get(0).getFormVersion()).isEqualTo(1);
        }
    }

    // ==================== 快照详情 ====================

    @Nested
    @DisplayName("快照详情")
    class GetSnapshot {

        @Test
        @DisplayName("按版本读取 → 返回该版本完整 definition（与写入内容一致）")
        void getSnapshot_returnsDefinitionOfThatVersion() {
            String formId = createDraftWithDefinition("snapshot_detail_case",
                    "{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\"}]}");
            String v1Def = "{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\"}]}";
            String v2Def = "{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\"},{\"name\":\"days\",\"type\":\"NUMBER\"}]}";
            insertSnapshot(formId, 1, v1Def);
            insertSnapshot(formId, 2, v2Def);

            FormSnapshotDetailDTO v1 = formDefService.getSnapshot(formId, 1);
            FormSnapshotDetailDTO v2 = formDefService.getSnapshot(formId, 2);

            assertThat(v1.getDefinition()).isEqualTo(v1Def);
            assertThat(v2.getDefinition()).isEqualTo(v2Def);
            assertThat(v1.getFormVersion()).isEqualTo(1);
            assertThat(v1.getCreateTime()).isNotNull();
        }

        @Test
        @DisplayName("版本不存在 → SNAPSHOT_NOT_FOUND(1301)")
        void getSnapshot_versionNotFound() {
            String formId = createDraftWithDefinition("snapshot_miss_case", "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"}]}");
            insertSnapshot(formId, 1, "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"}]}");

            assertThatThrownBy(() -> formDefService.getSnapshot(formId, 99))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining(FormErrorCode.SNAPSHOT_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("表单不存在 → FORM_NOT_FOUND(1000)")
        void getSnapshot_formNotFound() {
            assertThatThrownBy(() -> formDefService.getSnapshot("no-such-form", 1))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining(FormErrorCode.FORM_NOT_FOUND.getMessage());
        }

        @Test
        @DisplayName("逻辑删除的快照不可读取")
        void getSnapshot_excludesLogicDeleted() {
            String formId = createDraftWithDefinition("snapshot_read_deleted_case", "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"}]}");
            insertSnapshot(formId, 1, "{\"fields\":[{\"name\":\"a\",\"type\":\"TEXT\"}]}");
            jdbcTemplate.update("UPDATE sw_form_snapshot SET deleted = 1 WHERE form_id = ?", formId);

            assertThatThrownBy(() -> formDefService.getSnapshot(formId, 1))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining(FormErrorCode.SNAPSHOT_NOT_FOUND.getMessage());
        }
    }

    // ==================== 测试辅助方法 ====================

    /** 建一个带非空 definition 的草稿（definition 非空是发布的前置，保持与真实路径一致）。 */
    private String createDraftWithDefinition(String formKey, String definition) {
        FormDefDTO dto = formDefService.createDraft(formKey, "快照测试表单-" + formKey, null, null);
        createdFormIds.add(dto.getId());
        formDefService.saveConfig(dto.getId(), definition);
        return dto.getId();
    }

    /** 直接插入快照行（形状与 FormDefServiceImpl.publish Step 6 一致）。 */
    private void insertSnapshot(String formId, int version, String definition) {
        FormSnapshotEntity snapshot = new FormSnapshotEntity();
        snapshot.setId(new FormIdGenerator().generate());
        snapshot.setFormId(formId);
        snapshot.setFormVersion(version);
        snapshot.setDefinition(definition);
        snapshot.setCreateTime(java.time.LocalDateTime.now());
        snapshot.setUpdateTime(java.time.LocalDateTime.now());
        snapshot.setTenantId(0L);
        snapshot.setDeleted(0);
        snapshot.setVersion(0L);
        jdbcTemplate.update(
                "INSERT INTO sw_form_snapshot (id, form_id, form_version, definition, tenant_id, deleted, create_time, update_time, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.getId(), snapshot.getFormId(), snapshot.getFormVersion(), snapshot.getDefinition(),
                snapshot.getTenantId(), snapshot.getDeleted(),
                java.sql.Timestamp.valueOf(snapshot.getCreateTime()), java.sql.Timestamp.valueOf(snapshot.getUpdateTime()),
                snapshot.getVersion());
    }

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
    }

    // ==================== 测试装配 ====================

    @Configuration
    @MapperScan("com.sw.ck.form.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:formsnapshotquerytest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
