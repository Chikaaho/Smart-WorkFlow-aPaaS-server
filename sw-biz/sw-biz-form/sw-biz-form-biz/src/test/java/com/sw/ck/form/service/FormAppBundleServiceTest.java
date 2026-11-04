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
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.bundle.FormAppBundleDatasourcePort;
import com.sw.ck.form.api.bundle.FormAppBundleProcessPort;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormBundlePreviewResult;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.api.dict.DictTypeDetailDTO;
import com.sw.ck.system.api.user.UserOptionDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormAppDirMapper;
import com.sw.ck.form.mapper.FormAppImportRecordMapper;
import com.sw.ck.form.mapper.FormAppMapper;
import com.sw.ck.form.mapper.FormAppScopeMapper;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormExtQueryMapper;
import com.sw.ck.form.mapper.FormListConfigMapper;
import com.sw.ck.form.txn.mapper.TxnActionMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F2 应用整包导入导出（0.2.2，D022-10～15）行为证据（H2 集成）：
 * 双租户导出→预检→确认导入往返、包内引用按新 id/key 改写、外部引用按 D022-13
 * 匹配或置空并记报告、冲突自动处理（应用重名后缀/表单 key 重生成/意见表单用户确认）、
 * 单事务整体回滚、格式版本不兼容拒绝。流程端口与数据源端口用受控桩
 * （真实适配由 bpm 模块承接，行为等同生产适配的判定语义）。
 */
@SpringBootTest(classes = FormAppBundleServiceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("F2 应用整包导入导出：双租户往返/冲突/回滚（H2）")
class FormAppBundleServiceTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final Long TENANT_3 = 3L;
    private static final Long ADMIN = 11L;

    /** 源环境交易动作 UUID（TXN_ACTION 节点 actionId 引用） */
    private static final String SRC_ACTION_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FormAppBundleService bundleService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StubProcessPort stubProcessPort;
    @Autowired
    private StubDatasourcePort stubDatasourcePort;
    @Autowired
    private StubUserQueryFacade stubUserQueryFacade;

    private String exportedBundle;

    @Configuration
    @EnableTransactionManagement
    @MapperScan({"com.sw.ck.form.mapper", "com.sw.ck.form.txn.mapper"})
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url("jdbc:h2:mem:f022bundle;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public DataSourceTransactionManager transactionManager(DataSource dataSource) {
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
                DataSource dataSource, LoginContextProvider loginContextProvider) throws Exception {
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
            globalConfig.setMetaObjectHandler(new CommonMetaObjectHandler(loginContextProvider));
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
        public FormIdGenerator formIdGenerator() {
            return new FormIdGenerator();
        }

        /** 流程端口桩：记录写入并可控失败；导入失败用于验证单事务回滚。 */
        @Bean
        public StubProcessPort stubProcessPort() {
            return new StubProcessPort();
        }

        /** 数据源端口桩：按名称返回受控 id（真实适配按租户隔离查 sw_bpm_ext_datasource）。 */
        @Bean
        public StubDatasourcePort stubDatasourcePort() {
            return new StubDatasourcePort();
        }

        /** 字典 Facade 桩：同库 SQL 实现契约（真实实现在 system-biz，占用探测跨租户）。 */
        @Bean
        public StubDictFacade stubDictFacade(JdbcTemplate jdbcTemplate) {
            return new StubDictFacade(jdbcTemplate);
        }

        /** 角色/岗位 Facade 桩：内存映射编码 → 活跃成员（真实实现在 system-biz）。 */
        @Bean
        public StubUserQueryFacade stubUserQueryFacade() {
            return new StubUserQueryFacade();
        }

        @Bean
        public FormAppBundleService formAppBundleService(
                FormAppMapper formAppMapper, FormAppDirMapper formAppDirMapper,
                FormAppScopeMapper formAppScopeMapper, FormDefMapper formDefMapper,
                FormConfigMapper formConfigMapper, FormListConfigMapper formListConfigMapper,
                FormExtQueryMapper formExtQueryMapper, TxnActionMapper txnActionMapper,
                FormAppImportRecordMapper importRecordMapper, FormIdGenerator idGenerator,
                ObjectMapper objectMapper,
                StubProcessPort processPort, StubDatasourcePort datasourcePort,
                StubDictFacade dictFacade, StubUserQueryFacade userQueryFacade) {
            return new FormAppBundleService(formAppMapper, formAppDirMapper, formAppScopeMapper,
                    formDefMapper, formConfigMapper, formListConfigMapper, formExtQueryMapper,
                    txnActionMapper, importRecordMapper, idGenerator, objectMapper,
                    providerOf(processPort), providerOf(datasourcePort),
                    providerOf(dictFacade), providerOf(userQueryFacade));
        }

        private static <T> ObjectProvider<T> providerOf(T value) {
            return new ObjectProvider<T>() {
                @Override public T getObject() { return value; }
                @Override public T getIfAvailable() { return value; }
            };
        }
    }

    static class StubProcessPort implements FormAppBundleProcessPort {
        final List<BundleProcessExport> fixtures = new ArrayList<>();
        final List<BundleProcessImport> inserted = new ArrayList<>();
        boolean failInsert = false;
        long nextId = 900L;

        @Override
        public Optional<List<BundleProcessExport>> loadProcessesByMainFormKeys(
                java.util.Collection<String> formKeys) {
            if (formKeys == null || formKeys.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(fixtures.stream()
                    .filter(p -> formKeys.contains(p.formKey()))
                    .toList());
        }

        @Override
        public Optional<Long> insertDraftProcess(BundleProcessImport process) {
            if (failInsert) {
                throw new IllegalStateException("流程写入失败（受控桩）");
            }
            inserted.add(process);
            return Optional.of(nextId++);
        }

        @Override
        public Optional<Long> findCategoryIdByName(String name) {
            return "销售流程".equals(name) ? Optional.of(88L) : Optional.empty();
        }
    }

    static class StubDatasourcePort implements FormAppBundleDatasourcePort {
        final Map<String, Long> byName = new HashMap<>();
        final Map<Long, String> nameById = new HashMap<>();

        @Override
        public Optional<Long> findDatasourceIdByName(String name) {
            return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.trim()));
        }

        @Override
        public Optional<String> findDatasourceNameById(Long id) {
            return id == null ? Optional.empty() : Optional.ofNullable(nameById.get(id));
        }
    }

    /**
     * 字典 Facade 桩：以同库 SQL 实现 Facade 契约（导出详情/导入占用探测/导入创建），
     * 写入落在测试 H2 的 sys_dict_* 表并参与调用方事务（回滚断言仍以库表为准）；
     * 跨租户占用探测与生产 system-biz 的方法级 @InterceptorIgnore 探测同语义。
     */
    static class StubDictFacade implements DictFacade {
        private final JdbcTemplate jdbcTemplate;

        StubDictFacade(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public Optional<List<DictItemDTO>> listByType(String dictType) {
            if (dictType == null || dictType.isBlank()) {
                return Optional.empty();
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT label, dict_value, sort, status, is_default, css_class, list_class, description "
                            + "FROM sys_dict_data WHERE dict_code = ? AND tenant_id = ? AND deleted = 0 "
                            + "AND status = 0 ORDER BY sort, id", dictType, currentTenantId());
            return Optional.of(rows.stream().map(StubDictFacade::toDTO).toList());
        }

        @Override
        public Optional<Boolean> isValidCode(String dictType, String code) {
            if (dictType == null || dictType.isBlank() || code == null || code.isBlank()) {
                return Optional.empty();
            }
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_dict_data WHERE dict_code = ? AND dict_value = ? "
                            + "AND tenant_id = ? AND deleted = 0 AND status = 0",
                    Integer.class, dictType, code, currentTenantId());
            return Optional.of(n != null && n > 0);
        }

        @Override
        public Optional<Map<String, DictTypeDetailDTO>> findDictTypeDetails(Collection<String> codes) {
            if (codes == null || codes.isEmpty()) {
                return Optional.empty();
            }
            Long tenantId = currentTenantId();
            Map<String, DictTypeDetailDTO> result = new LinkedHashMap<>();
            for (String code : codes) {
                List<Map<String, Object>> types = jdbcTemplate.queryForList(
                        "SELECT code AS \"code\", name AS \"name\", status AS \"status\", "
                                + "description AS \"description\" FROM sys_dict_type "
                                + "WHERE code = ? AND tenant_id = ? AND deleted = 0 ORDER BY id",
                        code, tenantId);
                if (types.isEmpty()) {
                    continue;
                }
                Map<String, Object> type = types.get(0);
                List<DictItemDTO> items = jdbcTemplate.queryForList(
                        "SELECT label, dict_value, sort, status, is_default, css_class, list_class, description "
                                + "FROM sys_dict_data WHERE dict_code = ? AND tenant_id = ? AND deleted = 0 "
                                + "ORDER BY sort, id", code, tenantId)
                        .stream().map(StubDictFacade::toDTO).toList();
                result.put(String.valueOf(type.get("code")), DictTypeDetailDTO.builder()
                        .code(String.valueOf(type.get("code")))
                        .name((String) type.get("name"))
                        .status(((Number) type.get("status")).intValue())
                        .description((String) type.get("description"))
                        .items(items)
                        .build());
            }
            return Optional.of(result);
        }

        @Override
        public Optional<String> inspectDictTypeForImport(String code) {
            if (code == null || code.isBlank()) {
                return Optional.empty();
            }
            Integer inTenant = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_dict_type WHERE code = ? AND tenant_id = ? AND deleted = 0",
                    Integer.class, code, currentTenantId());
            if (inTenant != null && inTenant > 0) {
                return Optional.of("REUSE");
            }
            // 全局唯一索引 (code, deleted)：任一租户活跃行即占用（跨租户探测）
            List<Long> owners = jdbcTemplate.queryForList(
                    "SELECT DISTINCT tenant_id FROM sys_dict_type WHERE code = ? AND deleted = 0",
                    Long.class, code);
            return Optional.of(owners.isEmpty() ? "MISSING" : "OCCUPIED");
        }

        @Override
        public Optional<Boolean> createDictTypeForImport(String code, String name, Integer status,
                                                         String description, List<DictItemDTO> items) {
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                return Optional.empty();
            }
            if (!"MISSING".equals(inspectDictTypeForImport(code).orElse(null))) {
                return Optional.of(false);
            }
            Long tenantId = currentTenantId();
            try {
                jdbcTemplate.update("INSERT INTO sys_dict_type (id, name, code, status, description, "
                                + "tenant_id, deleted, version) VALUES (?, ?, ?, ?, ?, ?, 0, 0)",
                        nextId(), name, code.trim(), status == null ? 0 : status, description, tenantId);
            } catch (DuplicateKeyException e) {
                // 并发竞态撞全局唯一索引：与生产实现同语义返回 false
                return Optional.of(false);
            }
            if (items != null) {
                for (DictItemDTO item : items) {
                    if (item == null || item.getCode() == null || item.getCode().isBlank()) {
                        continue;
                    }
                    jdbcTemplate.update("INSERT INTO sys_dict_data (id, dict_code, label, dict_value, "
                                    + "sort, status, is_default, css_class, list_class, description, "
                                    + "tenant_id, deleted, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)",
                            nextId(), code.trim(), item.getLabel(), item.getCode(),
                            item.getSort() == null ? 0 : item.getSort(),
                            item.getStatus() == null ? 0 : item.getStatus(),
                            item.getIsDefault() == null ? 0 : item.getIsDefault(),
                            item.getCssClass(), item.getListClass(), item.getDescription(), tenantId);
                }
            }
            return Optional.of(true);
        }

        private Long currentTenantId() {
            LoginUser user = LoginUserHolder.get();
            return user == null ? null : user.getTenantId();
        }

        private long nextId() {
            return System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(0, 1000);
        }

        private static DictItemDTO toDTO(Map<String, Object> row) {
            return DictItemDTO.builder()
                    .dictType(null)
                    .code(text(row.get("dict_value")))
                    .label(text(row.get("label")))
                    .sort(intOf(row.get("sort")))
                    .status(intOf(row.get("status")))
                    .isDefault(intOf(row.get("is_default")))
                    .cssClass(text(row.get("css_class")))
                    .listClass(text(row.get("list_class")))
                    .description(text(row.get("description")))
                    .build();
        }

        private static String text(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static Integer intOf(Object value) {
            return value == null ? null : ((Number) value).intValue();
        }
    }

    /**
     * 角色/岗位 Facade 桩：内存映射 tenantId → 编码 → 活跃成员 id 列表；
     * 未装配成员的编码即"未匹配"（服务据此置空并记报告），与生产实现同语义。
     */
    static class StubUserQueryFacade implements UserQueryFacade {
        final Map<Long, Map<String, List<Long>>> roleMembers = new HashMap<>();
        final Map<Long, Map<String, List<Long>>> postMembers = new HashMap<>();

        @Override
        public Optional<List<Long>> findActiveUserIdsByRoleCodes(Collection<String> roleCodes,
                                                                 Long tenantId) {
            return members(roleMembers, roleCodes, tenantId);
        }

        @Override
        public Optional<List<Long>> findActiveUserIdsByPostCodes(Collection<String> postCodes,
                                                                 Long tenantId) {
            return members(postMembers, postCodes, tenantId);
        }

        private Optional<List<Long>> members(Map<Long, Map<String, List<Long>>> source,
                                             Collection<String> codes, Long tenantId) {
            if (codes == null || codes.isEmpty() || tenantId == null) {
                return Optional.empty();
            }
            List<Long> result = new ArrayList<>();
            for (String code : codes) {
                result.addAll(source.getOrDefault(tenantId, Map.of()).getOrDefault(code, List.of()));
            }
            return Optional.of(result);
        }

        // 以下方法本测试不消费，按契约最小占位
        @Override
        public Optional<List<UserOptionDTO>> searchActiveUsers(String keyword, int limit) {
            return Optional.of(List.of());
        }

        @Override
        public Optional<Map<Long, String>> getUserDisplayNames(Collection<Long> ids) {
            return ids == null ? Optional.empty() : Optional.of(Map.of());
        }

        @Override
        public Optional<List<Long>> findActiveUserIds(Collection<Long> ids) {
            return ids == null ? Optional.empty() : Optional.of(List.of());
        }

        @Override
        public Optional<List<Long>> findActiveUserIds(Collection<Long> ids, Long tenantId) {
            return ids == null || tenantId == null ? Optional.empty() : Optional.of(List.of());
        }

        @Override
        public Optional<List<Long>> findActiveUserIdsByRoleCodes(Collection<String> roleCodes) {
            return roleCodes == null || roleCodes.isEmpty()
                    ? Optional.empty() : Optional.of(List.of());
        }

        @Override
        public Optional<List<Long>> findActiveUserIdsByDeptLeaders(Collection<Long> deptIds, Long tenantId) {
            return Optional.of(List.of());
        }

        @Override
        public Optional<Map<Long, Long>> findDeptLeaderMap(Collection<Long> deptIds, Long tenantId) {
            return Optional.of(Map.of());
        }

        @Override
        public Optional<List<Long>> findActiveUserIdsByDeptAndPost(Long deptId, String postCode, Long tenantId) {
            return Optional.of(List.of());
        }
    }

    @BeforeEach
    void setUp() {
        createTables();
        stubProcessPort.fixtures.clear();
        stubProcessPort.inserted.clear();
        stubProcessPort.failInsert = false;
        stubDatasourcePort.byName.clear();
        stubDatasourcePort.nameById.clear();
        stubUserQueryFacade.roleMembers.clear();
        stubUserQueryFacade.postMembers.clear();
        seedTenant1Fixtures();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        for (String table : List.of("sw_form_app_import_record", "sw_form_txn_action",
                "sw_form_ext_query", "sw_form_list_config", "sw_form_config", "sw_form_def",
                "sw_form_app_scope", "sw_form_app_dir", "sw_form_app",
                "sw_bpm_ext_datasource", "sys_dict_data", "sys_dict_type")) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }

    // ============================== 造数 ==============================

    private void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_app (
                    id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL,
                    icon VARCHAR(100), color VARCHAR(50), sort_no INT NOT NULL DEFAULT 0,
                    visible_scope_type VARCHAR(20) NOT NULL DEFAULT 'ALL',
                    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
                    source VARCHAR(30), legacy_category_id BIGINT,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_app_dir (
                    id VARCHAR(36) PRIMARY KEY, app_id VARCHAR(36) NOT NULL,
                    name VARCHAR(100) NOT NULL, sort_no INT NOT NULL DEFAULT 0,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_app_scope (
                    id VARCHAR(36) PRIMARY KEY, app_id VARCHAR(36) NOT NULL,
                    scope_type VARCHAR(20) NOT NULL, scope_id VARCHAR(64),
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_def (
                    id VARCHAR(36) PRIMARY KEY, form_key VARCHAR(100) NOT NULL,
                    form_type VARCHAR(20) NOT NULL DEFAULT 'BUSINESS',
                    name VARCHAR(200) NOT NULL, logical_table_name VARCHAR(100),
                    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', physical_table_name VARCHAR(100),
                    form_version INT NOT NULL DEFAULT 1, description VARCHAR(500),
                    visibility_scope TEXT, sub_table_mapping TEXT,
                    app_id VARCHAR(36), dir_id VARCHAR(36),
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
                CREATE TABLE IF NOT EXISTS sw_form_list_config (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL,
                    config_json CLOB,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        // 与生产 V0.2.0 同列结构：datasource_id NOT NULL、(tenant, key, version) 唯一
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_ext_query (
                    id VARCHAR(36) PRIMARY KEY,
                    datasource_id BIGINT NOT NULL,
                    query_key VARCHAR(64) NOT NULL,
                    query_version INT NOT NULL DEFAULT 1,
                    sql_text CLOB NOT NULL, output_fields CLOB NOT NULL,
                    enabled SMALLINT NOT NULL DEFAULT 1,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0,
                    CONSTRAINT uk_sw_form_ext_query_t UNIQUE (tenant_id, query_key, query_version))
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_txn_action (
                    id VARCHAR(36) PRIMARY KEY, form_id VARCHAR(36) NOT NULL,
                    action_key VARCHAR(100) NOT NULL, name VARCHAR(200) NOT NULL,
                    action_type VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                    current_version INT, config_json CLOB, description VARCHAR(500),
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_form_app_import_record (
                    id VARCHAR(36) PRIMARY KEY, package_name VARCHAR(200) NOT NULL,
                    file_name VARCHAR(255), app_id VARCHAR(36), app_name VARCHAR(200),
                    result VARCHAR(20) NOT NULL, report_json TEXT,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sys_dict_type (
                    id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, code VARCHAR(50) NOT NULL,
                    status SMALLINT NOT NULL DEFAULT 0, description TEXT,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_dict_type_code_t "
                + "ON sys_dict_type (code, deleted)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sys_dict_data (
                    id BIGINT PRIMARY KEY, dict_code VARCHAR(50) NOT NULL,
                    label VARCHAR(100) NOT NULL, dict_value VARCHAR(100) NOT NULL,
                    sort INT NOT NULL DEFAULT 0, status SMALLINT NOT NULL DEFAULT 0,
                    is_default SMALLINT NOT NULL DEFAULT 0, css_class VARCHAR(50), list_class VARCHAR(50),
                    description TEXT,
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_by BIGINT,
                    version BIGINT NOT NULL DEFAULT 0)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_bpm_ext_datasource (
                    id BIGINT PRIMARY KEY, name VARCHAR(100),
                    tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT NOT NULL DEFAULT 0)
                """);
    }

    /** 租户1（源环境）：应用 CRM + 双表单（REFERENCE/DICT/DATASOURCE/字段权限）+ 意见表单 + 流程 + 交易动作。 */
    private void seedTenant1Fixtures() {
        login(ADMIN, TENANT_1);
        // 角色/岗位存在性经 StubUserQueryFacade（角色 manager 在租户1有活跃成员 5）
        stubUserQueryFacade.roleMembers.put(TENANT_1, Map.of("manager", List.of(5L)));
        stubUserQueryFacade.postMembers.put(TENANT_1, Map.of("chief", List.of(5L)));
        jdbcTemplate.update("INSERT INTO sys_dict_type (id, name, code, status, tenant_id, deleted, version) "
                + "VALUES (10, '颜色', 'dict_color', 0, ?, 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sys_dict_data (id, dict_code, label, dict_value, sort, tenant_id, deleted, version) "
                + "VALUES (11, 'dict_color', '红', 'RED', 1, ?, 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sys_dict_data (id, dict_code, label, dict_value, sort, tenant_id, deleted, version) "
                + "VALUES (12, 'dict_color', '蓝', 'BLUE', 2, ?, 0, 0)", TENANT_1);
        // 数据源名称出包经 StubDatasourcePort.nameById（真实适配按租户隔离读 bpm 侧）
        stubDatasourcePort.nameById.put(9L, "报表库");
        jdbcTemplate.update("INSERT INTO sw_form_ext_query (id, datasource_id, query_key, query_version, "
                + "sql_text, output_fields, enabled, tenant_id, deleted, version) "
                + "VALUES ('q-1', 9, 'q1', 1, 'SELECT 1', '[{\"name\":\"v\"}]', 1, ?, 0, 0)", TENANT_1);

        jdbcTemplate.update("INSERT INTO sw_form_app (id, name, icon, color, sort_no, visible_scope_type, "
                + "status, source, tenant_id, deleted, version) "
                + "VALUES ('app-src', 'CRM', 'app-icon', '#3366ff', 5, 'SPECIFIED', 'ENABLED', 'MANUAL', ?, 0, 0)",
                TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_form_app_scope (id, app_id, scope_type, scope_id, tenant_id, deleted, version) "
                + "VALUES ('sc-1', 'app-src', 'ROLE', 'manager', ?, 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_form_app_scope (id, app_id, scope_type, scope_id, tenant_id, deleted, version) "
                + "VALUES ('sc-2', 'app-src', 'USER', '5', ?, 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_form_app_scope (id, app_id, scope_type, scope_id, tenant_id, deleted, version) "
                + "VALUES ('sc-3', 'app-src', 'TENANT', '1', ?, 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_form_app_dir (id, app_id, name, sort_no, tenant_id, deleted, version) "
                + "VALUES ('dir-src', 'app-src', '客户', 1, ?, 0, 0)", TENANT_1);

        String leadDef = """
                {"fields":[
                  {"name":"name","type":"TEXT","label":"名称"},
                  {"name":"opp","type":"REFERENCE","label":"商机","targetFormId":"opp_form"},
                  {"name":"color","type":"DICT","label":"颜色","dictType":"dict_color"},
                  {"name":"ds","type":"DATASOURCE","label":"数据源对象",
                   "dsBinding":{"queryKey":"q1","version":1,"valueField":"v","displayField":"v"}}],
                 "fieldPermissions":{"amount":{"view":["role:manager","user:5"],"edit":["role:manager"]}}}
                """;
        insertSourceForm("f-lead", "lead_form", "线索", "BUSINESS", leadDef, "{\"userIds\":[5]}", "app-src", "dir-src");
        insertSourceForm("f-opp", "opp_form", "商机", "BUSINESS", "{\"fields\":[{\"name\":\"t\",\"type\":\"TEXT\"}]}",
                null, "app-src", null);
        insertSourceForm("f-op", "op_form", "审批意见", "APPROVAL_COMMENT", "{\"fields\":[{\"name\":\"c\",\"type\":\"TEXT\"}]}",
                null, null, null);
        jdbcTemplate.update("INSERT INTO sw_form_list_config (id, form_id, config_json, tenant_id, deleted, version) "
                + "VALUES ('lc-1', 'f-lead', '{\"columns\":[\"name\"]}', ?, 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_form_txn_action (id, form_id, action_key, name, action_type, "
                + "status, current_version, config_json, tenant_id, deleted, version) "
                + "VALUES (?, 'f-lead', 'act1', '预留库存', 'RESERVE', 'PUBLISHED', 2, '{\"sql\":\"x\"}', ?, 0, 0)",
                SRC_ACTION_ID, TENANT_1);

        String graph = """
                {"processKey":"bpm_src","name":"线索流程","version":3,"elements":[
                  {"id":"n1","kind":"node","type":"APPROVAL","config":{"name":"审批",
                    "nodeForm":{"formKey":"op_form","formVersion":2},
                    "opinionForm":{"formId":"f-op","version":2,"fields":[]},
                    "participant":{"strategy":"FIXED_USER","value":["5"]}}},
                  {"id":"n2","kind":"node","type":"APPROVAL","config":{"name":"复核",
                    "participant":{"strategy":"ROLE","value":["manager","ghost"]},
                    "approver":{"strategy":"DEPT_LEADER","value":"9"}}},
                  {"id":"n3","kind":"node","type":"TXN_ACTION","config":{"name":"扣减","actionId":"%s"}}],
                 "triggers":[{"branches":[{"actions":[{"actionId":"a1","type":"START_PROCESS",
                   "targetProcessDefKey":"bpm_other","targetFormKey":"lead_form","sourceVariable":"v1"}]}]}],
                 "notification":{"initiator":true,"approvers":true,"otherRecipientIds":[7,8],"channels":["INAPP"]},
                 "variables":[],"canvas":{}}
                """.formatted(SRC_ACTION_ID);
        stubProcessPort.fixtures.add(new FormAppBundleProcessPort.BundleProcessExport(
                900L, "bpm_src", "线索流程", "lead_form", "{\"dark\":\"#000\"}", true,
                "{\"enabled\":true,\"deviceSource\":\"FIXED\",\"deviceId\":42,\"commandKey\":\"on\"}",
                "销售流程", graph));

        exportedBundle = null;
    }

    private void insertSourceForm(String id, String formKey, String name, String formType,
                                  String definition, String visibilityScope, String appId, String dirId) {
        jdbcTemplate.update("INSERT INTO sw_form_def (id, form_key, name, form_type, status, form_version, "
                        + "visibility_scope, app_id, dir_id, physical_table_name, tenant_id, deleted, version) "
                        + "VALUES (?, ?, ?, ?, 'PUBLISHED', 3, ?, ?, ?, 'sw_form_src', ?, 0, 0)",
                id, formKey, name, formType, visibilityScope, appId, dirId, TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_form_config (id, form_id, table_name, parent_table, definition, "
                + "tenant_id, deleted, version) VALUES (?, ?, ?, NULL, ?, ?, 0, 0)",
                "cfg-" + id, id, "sw_form_src", definition, TENANT_1);
    }

    private void login(Long userId, Long tenantId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        user.setRoles(List.of());
        user.setPermissions(List.of());
        LoginUserHolder.set(user);
    }

    /** 以租户1身份导出包（缓存一次），导出后恢复原登录上下文，返回字节。 */
    private byte[] bundleBytes() {
        if (exportedBundle == null) {
            com.sw.ck.security.holder.LoginUser current = LoginUserHolder.get();
            login(ADMIN, TENANT_1);
            try {
                exportedBundle = new String(
                        bundleService.exportAppBundle("app-src").content(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                LoginUserHolder.set(current);
            }
        }
        return exportedBundle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 在导出包基础上把字典编码改为目标租户全库不存在的编码（验证缺失新建路径）。 */
    private byte[] bundleBytesWithFreshDict() throws Exception {
        Map<String, Object> doc = objectMapper.readValue(bundleBytes(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        List<Map<String, Object>> dictTypes = (List<Map<String, Object>>) doc.get("dictTypes");
        for (Map<String, Object> type : dictTypes) {
            type.put("code", "dict_fresh");
        }
        List<Map<String, Object>> forms = (List<Map<String, Object>>) doc.get("forms");
        for (Map<String, Object> form : forms) {
            Map<String, Object> def = objectMapper.readValue(
                    String.valueOf(form.get("configDefinition")),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            List<Map<String, Object>> fields = (List<Map<String, Object>>) def.get("fields");
            for (Map<String, Object> field : fields) {
                if ("DICT".equals(field.get("type"))) {
                    field.put("dictType", "dict_fresh");
                }
            }
            form.put("configDefinition", objectMapper.writeValueAsString(def));
        }
        return objectMapper.writeValueAsBytes(doc);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ============================== 用例 ==============================

    @Test
    @DisplayName("往返：无冲突租户导入——全部新建为草稿，包内引用按新 id/key 改写，外部引用按 D022-13 处置")
    void roundTripImportIntoCleanTenant() throws Exception {
        stubDatasourcePort.byName.put("报表库", 77L);
        // 目标租户存在同编码角色 manager 且有活跃成员（角色按编码跨租户独立匹配）
        stubUserQueryFacade.roleMembers.put(TENANT_2, Map.of("manager", List.of(21L)));
        login(ADMIN, TENANT_2);

        FormBundlePreviewResult preview = bundleService.previewBundle(bundleBytes());
        assertThat(preview.getCounts().getForms()).isEqualTo(2);
        assertThat(preview.getCounts().getProcesses()).isEqualTo(1);
        assertThat(preview.getCounts().getOpinionForms()).isEqualTo(1);
        assertThat(preview.getCounts().getDirs()).isEqualTo(1);
        assertThat(preview.getCounts().getTxnActions()).isEqualTo(1);
        assertThat(preview.getCounts().getExtQueries()).isEqualTo(1);
        assertThat(preview.getCounts().getDictTypes()).isEqualTo(1);
        assertThat(preview.getApp().getNameConflict()).isFalse();
        assertThat(preview.getOpinionForms().get(0).getConflict()).isFalse();
        // 生产唯一索引 (code, deleted) 全局：源租户活跃持有 dict_color → 目标租户只能报 OCCUPIED
        assertThat(preview.getDictTypes().get(0).getAction()).isEqualTo("OCCUPIED");

        // 字典改为全库不存在的编码后：CREATE 路径——新建类型与字典项、字段编码保留
        var report = bundleService.confirmImport(bundleBytesWithFreshDict(), null);

        // 应用与可见范围：全部新建；USER 主体置空，ROLE 命中与 TENANT 保留
        assertThat(report.getAppId()).isNotEqualTo("app-src");
        assertThat(report.getFinalAppName()).isEqualTo("CRM");
        assertThat(count("SELECT COUNT(*) FROM sw_form_app WHERE name = 'CRM' AND tenant_id = 2")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_scope WHERE scope_type = 'USER' AND tenant_id = 2"))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_scope WHERE scope_type = 'ROLE' "
                + "AND scope_id = 'manager' AND tenant_id = 2")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_scope WHERE scope_type = 'TENANT' AND tenant_id = 2"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_dir WHERE name = '客户' AND tenant_id = 2")).isEqualTo(1);

        // 表单：key 保持、id 全新、草稿、v1、无物理表；目录归属映射到新目录
        List<Map<String, Object>> forms = jdbcTemplate.queryForList(
                "SELECT id AS \"id\", form_key AS \"form_key\", dir_id AS \"dir_id\", "
                        + "form_version AS \"form_version\", physical_table_name AS \"physical_table_name\", "
                        + "status AS \"status\", visibility_scope AS \"visibility_scope\" "
                        + "FROM sw_form_def WHERE tenant_id = 2 AND form_type = 'BUSINESS' ORDER BY form_key");
        assertThat(forms).hasSize(2);
        Map<String, Map<String, Object>> formByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : forms) {
            formByKey.put(String.valueOf(row.get("form_key")), row);
        }
        assertThat(formByKey).containsKeys("lead_form", "opp_form");
        Object newLeadId = formByKey.get("lead_form").get("id");
        assertThat(newLeadId).isNotEqualTo("f-lead");
        assertThat(formByKey.get("lead_form").get("status")).isEqualTo("DRAFT");
        assertThat(((Number) formByKey.get("lead_form").get("form_version")).intValue()).isEqualTo(1);
        assertThat(formByKey.get("lead_form").get("physical_table_name")).isNull();
        String newDirId = String.valueOf(formByKey.get("lead_form").get("dir_id"));
        assertThat(newDirId).isNotEqualTo("dir-src");
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_dir WHERE id = ? AND tenant_id = 2", newDirId))
                .isEqualTo(1);
        // 发起可见范围 userIds 置空
        assertThat(formByKey.get("lead_form").get("visibility_scope")).isNull();

        // 定义改写：REFERENCE 保 key、字段权限去 user: 保 role:、dsBinding 保 key（数据源匹配）
        String leadDef = jdbcTemplate.queryForObject(
                "SELECT c.definition FROM sw_form_config c JOIN sw_form_def d ON c.form_id = d.id "
                        + "WHERE d.form_key = 'lead_form' AND d.tenant_id = 2 AND c.parent_table IS NULL",
                String.class);
        Map<String, Object> defMap = objectMapper.readValue(leadDef, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        List<Map<String, Object>> fields = (List<Map<String, Object>>) defMap.get("fields");
        Map<String, Object> refField = fields.stream()
                .filter(f -> "REFERENCE".equals(f.get("type"))).findFirst().orElseThrow();
        assertThat(refField.get("targetFormId")).isEqualTo("opp_form");
        Map<String, Object> dictField = fields.stream()
                .filter(f -> "DICT".equals(f.get("type"))).findFirst().orElseThrow();
        assertThat(dictField.get("dictType")).isEqualTo("dict_fresh");
        Map<String, Object> perms = (Map<String, Object>) defMap.get("fieldPermissions");
        List<Object> view = (List<Object>) ((Map<String, Object>) perms.get("amount")).get("view");
        assertThat(view).containsExactly("role:manager");
        Map<String, Object> dsBinding = (Map<String, Object>) fields.stream()
                .filter(f -> "DATASOURCE".equals(f.get("type"))).findFirst().orElseThrow().get("dsBinding");
        assertThat(dsBinding.get("queryKey")).isEqualTo("q1");

        // 扩展查询契约：新行同 key，数据源按名称匹配（桩 77）
        assertThat(count("SELECT COUNT(*) FROM sw_form_ext_query WHERE query_key = 'q1' "
                + "AND query_version = 1 AND datasource_id = 77 AND tenant_id = 2")).isEqualTo(1);

        // 交易动作：草稿、同 actionKey、新 id
        Map<String, Object> action = jdbcTemplate.queryForMap(
                "SELECT id, action_key, status, current_version FROM sw_form_txn_action "
                        + "WHERE tenant_id = 2 AND action_key = 'act1'");
        assertThat(action.get("id")).isNotEqualTo(SRC_ACTION_ID);
        assertThat(action.get("status")).isEqualTo("DRAFT");
        assertThat(action.get("current_version")).isNull();

        // 流程：新 key 草稿；图内引用逐项改写（nodeForm/opinionForm/TXN_ACTION/参与人/触发器/通知/IoT）
        assertThat(stubProcessPort.inserted).hasSize(1);
        FormAppBundleProcessPort.BundleProcessImport proc = stubProcessPort.inserted.get(0);
        assertThat(proc.processKey()).startsWith("bpm_").isNotEqualTo("bpm_src");
        assertThat(proc.formKey()).isEqualTo("lead_form");
        assertThat(proc.categoryId()).isEqualTo(88L);
        assertThat(proc.iotAccessEnabled()).isTrue();
        Map<String, Object> newGraph = objectMapper.readValue(proc.graphJson(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(newGraph.get("processKey")).isEqualTo(proc.processKey());
        List<Map<String, Object>> elements = (List<Map<String, Object>>) newGraph.get("elements");
        Map<String, Object> n1 = elements.get(0);
        Map<String, Object> n1Cfg = (Map<String, Object>) n1.get("config");
        assertThat(((Map<String, Object>) n1Cfg.get("nodeForm")).get("formKey")).isEqualTo("op_form");
        assertThat(((Number) ((Map<String, Object>) n1Cfg.get("nodeForm")).get("formVersion")).intValue())
                .isEqualTo(1);
        String newOpinionId = jdbcTemplate.queryForObject(
                "SELECT id FROM sw_form_def WHERE tenant_id = 2 AND form_key = 'op_form' "
                        + "AND form_type = 'APPROVAL_COMMENT'", String.class);
        assertThat(((Map<String, Object>) n1Cfg.get("opinionForm")).get("formId")).isEqualTo(newOpinionId);
        assertThat(((Number) ((Map<String, Object>) n1Cfg.get("opinionForm")).get("version")).intValue())
                .isEqualTo(1);
        Map<String, Object> fixedUser = (Map<String, Object>) n1Cfg.get("participant");
        assertThat(fixedUser.get("strategy")).isEqualTo("FIXED_USER");
        assertThat(fixedUser.get("value")).isNull();
        Map<String, Object> n2Cfg = (Map<String, Object>) elements.get(1).get("config");
        Map<String, Object> roleParticipant = (Map<String, Object>) n2Cfg.get("participant");
        assertThat((List<Object>) roleParticipant.get("value")).containsExactly("manager");
        Map<String, Object> deptLeader = (Map<String, Object>) n2Cfg.get("approver");
        assertThat(deptLeader.get("value")).isNull();
        Map<String, Object> n3Cfg = (Map<String, Object>) elements.get(2).get("config");
        assertThat(n3Cfg.get("actionId")).isEqualTo(action.get("id"));
        List<Map<String, Object>> triggers = (List<Map<String, Object>>) newGraph.get("triggers");
        Map<String, Object> triggerAction = (Map<String, Object>) ((List<Object>) ((Map<String, Object>)
                ((List<Object>) triggers.get(0).get("branches")).get(0)).get("actions")).get(0);
        assertThat(triggerAction.get("targetProcessDefKey")).isNull();
        assertThat(triggerAction.get("targetFormKey")).isEqualTo("lead_form");
        Map<String, Object> notification = (Map<String, Object>) newGraph.get("notification");
        assertThat(notification.get("otherRecipientIds")).isNull();
        assertThat((List<Object>) notification.get("channels")).containsExactly("INAPP");
        assertThat(proc.iotDeviceActionJson()).contains("\"commandKey\":\"on\"").doesNotContain("42");

        // 字典：目标全库无该编码 → 新建类型与字典项（CREATE，tenant 2）
        assertThat(count("SELECT COUNT(*) FROM sys_dict_type WHERE code = 'dict_fresh' AND tenant_id = 2"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sys_dict_data WHERE dict_code = 'dict_fresh' AND tenant_id = 2"))
                .isEqualTo(2);

        // 报告与记录：置空清单 + 记录随导入落库
        assertThat(report.getClearedRefs()).extracting("kind")
                .contains("USER", "DEPT", "PROCESS", "IOT_DEVICE");
        assertThat(report.getMatchedDatasources()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_import_record WHERE tenant_id = 2 "
                + "AND result = 'SUCCESS' AND app_id = ?", report.getAppId())).isEqualTo(1);
    }

    @Test
    @DisplayName("冲突：应用重名加后缀、表单 key 重生成、意见表单按用户确认新 key、被占字典报 OCCUPIED")
    void conflictResolutionAndNoOverwrite() throws Exception {
        stubDatasourcePort.byName.put("报表库", 77L);
        // 目标租户既有同名应用、同 key 业务表单、同 key 意见表单；
        // dict_color 被源租户（tenant 1）活跃持有 → 目标租户视角即被占用
        login(ADMIN, TENANT_2);
        jdbcTemplate.update("INSERT INTO sw_form_app (id, name, sort_no, tenant_id, deleted, version) "
                + "VALUES ('app-t2', 'CRM', 0, ?, 0, 0)", TENANT_2);
        jdbcTemplate.update("INSERT INTO sw_form_def (id, form_key, name, form_type, status, form_version, "
                + "tenant_id, deleted, version) VALUES ('f-t2-lead', 'lead_form', '既有线索', 'BUSINESS', "
                + "'PUBLISHED', 1, ?, 0, 0)", TENANT_2);
        jdbcTemplate.update("INSERT INTO sw_form_def (id, form_key, name, form_type, status, form_version, "
                + "tenant_id, deleted, version) VALUES ('f-t2-op', 'op_form', '既有意见', 'APPROVAL_COMMENT', "
                + "'PUBLISHED', 1, ?, 0, 0)", TENANT_2);

        FormBundlePreviewResult preview = bundleService.previewBundle(bundleBytes());
        assertThat(preview.getApp().getNameConflict()).isTrue();
        assertThat(preview.getForms()).extracting("formKey", "keyConflict")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("lead_form", true),
                        org.assertj.core.groups.Tuple.tuple("opp_form", false));
        assertThat(preview.getOpinionForms().get(0).getConflict()).isTrue();
        assertThat(preview.getOpinionForms().get(0).getSuggestedKey()).startsWith("op_form_");
        assertThat(preview.getDictTypes().get(0).getAction()).isEqualTo("OCCUPIED");

        // 意见表单冲突未解决 → 结构化拒绝，且零写入
        assertThatThrownBy(() -> bundleService.confirmImport(bundleBytes(), null))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.BUNDLE_OPINION_KEY_REQUIRED.getCode()));
        assertThat(count("SELECT COUNT(*) FROM sw_form_app WHERE tenant_id = 2")).isEqualTo(1);

        String opinionKeys = "[{\"formKey\":\"op_form\",\"newKey\":\"op_form_new\"}]";
        var report = bundleService.confirmImport(bundleBytes(), opinionKeys);

        // 应用重名 → 后缀；目标既有对象零覆盖
        assertThat(report.getFinalAppName()).isEqualTo("CRM-导入");
        assertThat(count("SELECT COUNT(*) FROM sw_form_app WHERE name = 'CRM' AND tenant_id = 2")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sw_form_app WHERE name = 'CRM-导入' AND tenant_id = 2"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM sw_form_def WHERE id = 'f-t2-lead'", String.class)).isEqualTo("既有线索");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM sw_form_def WHERE id = 'f-t2-op'", String.class)).isEqualTo("既有意见");

        // 业务表单 key 冲突 → 重生成；未冲突 key 保持
        String newLeadKey = jdbcTemplate.queryForObject(
                "SELECT form_key FROM sw_form_def WHERE tenant_id = 2 AND name = '线索' "
                        + "AND form_type = 'BUSINESS'", String.class);
        assertThat(newLeadKey).startsWith("lead_form_").isNotEqualTo("lead_form");
        assertThat(count("SELECT COUNT(*) FROM sw_form_def WHERE tenant_id = 2 AND form_key = 'opp_form' "
                + "AND form_type = 'BUSINESS'")).isEqualTo(1);
        assertThat(report.getRegeneratedFormKeys()).hasSize(1);

        // 意见表单按用户确认的新 key 创建；图内 nodeForm 同步改写
        String newOpKey = jdbcTemplate.queryForObject(
                "SELECT form_key FROM sw_form_def WHERE tenant_id = 2 AND form_key = 'op_form_new'",
                String.class);
        assertThat(newOpKey).isEqualTo("op_form_new");
        FormAppBundleProcessPort.BundleProcessImport proc = stubProcessPort.inserted.get(0);
        Map<String, Object> newGraph = objectMapper.readValue(proc.graphJson(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        Map<String, Object> n1Cfg = (Map<String, Object>)
                ((List<Map<String, Object>>) newGraph.get("elements")).get(0).get("config");
        assertThat(((Map<String, Object>) n1Cfg.get("nodeForm")).get("formKey")).isEqualTo("op_form_new");

        // 被占字典：不新建、不报错，计入报告；字段级 dictType 同步置空并记字段位置
        // （D022-13 匹配不上的置空——编码被其他租户占用后在本租户永不可解析）
        assertThat(count("SELECT COUNT(*) FROM sys_dict_type WHERE code = 'dict_color' AND tenant_id = 2"))
                .isZero();
        assertThat(report.getClearedRefs()).extracting("kind").contains("DICT_OCCUPIED");
        String occupiedLeadDef = jdbcTemplate.queryForObject(
                "SELECT c.definition FROM sw_form_config c JOIN sw_form_def d ON c.form_id = d.id "
                        + "WHERE d.form_key = '" + newLeadKey + "' AND d.tenant_id = 2 "
                        + "AND c.parent_table IS NULL",
                String.class);
        Map<String, Object> occupiedDefMap = objectMapper.readValue(occupiedLeadDef,
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        List<Map<String, Object>> occupiedFields =
                (List<Map<String, Object>>) occupiedDefMap.get("fields");
        Map<String, Object> occupiedDictField = occupiedFields.stream()
                .filter(f -> "DICT".equals(f.get("type"))).findFirst().orElseThrow();
        assertThat(occupiedDictField.get("dictType")).isNull();
        assertThat(report.getClearedRefs()).extracting("kind", "location")
                .contains(org.assertj.core.groups.Tuple.tuple("DICT", "表单 线索 · 字段 颜色 · 字典"));
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_import_record WHERE tenant_id = 2")).isEqualTo(1);
    }

    @Test
    @DisplayName("回滚：流程写入失败 → 应用/表单/字典/记录全部不落库（单事务整体回滚）")
    void rollbackWhenLateStepFails() {
        stubDatasourcePort.byName.put("报表库", 77L);
        login(ADMIN, TENANT_2);
        stubProcessPort.failInsert = true;

        assertThatThrownBy(() -> bundleService.confirmImport(bundleBytes(), null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT COUNT(*) FROM sw_form_app WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_def WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_config WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_ext_query WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_txn_action WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_dict_type WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_scope WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_dir WHERE tenant_id = 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM sw_form_app_import_record WHERE tenant_id = 2")).isZero();
    }

    @Test
    @DisplayName("边界：包格式版本不兼容直接拒绝；数据源未匹配时契约行不落库且 dsBinding 置空")
    void formatRejectAndUnmatchedDatasource() throws Exception {
        login(ADMIN, TENANT_2);

        // 篡改 bundleFormatVersion → 结构化拒绝
        Map<String, Object> tampered = objectMapper.readValue(bundleBytes(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        tampered.put("bundleFormatVersion", 999);
        byte[] tamperedBytes = objectMapper.writeValueAsBytes(tampered);
        assertThatThrownBy(() -> bundleService.previewBundle(tamperedBytes))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode())
                        .isEqualTo(FormErrorCode.BUNDLE_FORMAT_UNSUPPORTED.getCode()));

        // 数据源未匹配：契约行不落库（datasource_id NOT NULL），dsBinding 置空并记报告
        var report = bundleService.confirmImport(bundleBytes(), null);
        assertThat(count("SELECT COUNT(*) FROM sw_form_ext_query WHERE tenant_id = 2")).isZero();
        String leadDef = jdbcTemplate.queryForObject(
                "SELECT c.definition FROM sw_form_config c JOIN sw_form_def d ON c.form_id = d.id "
                        + "WHERE d.form_key = 'lead_form' AND d.tenant_id = 2 AND c.parent_table IS NULL",
                String.class);
        Map<String, Object> defMap = objectMapper.readValue(leadDef, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        List<Map<String, Object>> fields = (List<Map<String, Object>>) defMap.get("fields");
        Map<String, Object> dsBinding = (Map<String, Object>) fields.stream()
                .filter(f -> "DATASOURCE".equals(f.get("type"))).findFirst().orElseThrow().get("dsBinding");
        assertThat(dsBinding.get("queryKey")).isNull();
        assertThat(dsBinding.get("version")).isNull();
        assertThat(report.getClearedRefs()).extracting("kind").contains("DATASOURCE");
    }
}
