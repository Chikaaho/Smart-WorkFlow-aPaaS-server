package com.sw.ck.agent.service.impl;

import com.sw.ck.agent.dto.AgentProviderDTO;
import com.sw.ck.agent.dto.AgentProviderSaveReqDTO;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.mapper.AgentProviderMapper;
import com.sw.ck.agent.service.AgentProviderService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentProviderServiceImpl} 测试（F1 阶段 1，D022-02）。
 * <p>
 * 原 {@code AgentModelConfigServiceImplTest}（大模型接入配置单表 CRUD）已随
 * AgentModelConfig 拆分为 AgentProvider + AgentModel 而删除：供应商侧用例
 * （加密落库/空 Key 不覆盖/脱敏/名称唯一/白名单/分页）由本类承接，模型侧用例
 * 由 {@code AgentModelManageServiceImplTest} 承接。连通性测试语义改为真实最小
 * 调用（模型侧覆盖），本类不再覆盖 HTTP 探测类用例。
 * </p>
 * <p>
 * 策略：{@code @SpringBootTest} + H2（TestConfig 组合装配，参照
 * NotifyControllerIntegrationTest 先例）+ {@code @Transactional}（每用例回滚）。
 * 测试密钥（fake key）同样走加密流程：断言落库为密文且可被 decrypt 还原，
 * 绝不断言明文落库（方案 §8 硬约束）。
 * </p>
 */
@SpringBootTest(
        classes = AgentProviderServiceImplTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 排除 sw-common / sw-security 自动配置，避免与 TestConfig 手动装配的
                // MetaObjectHandler/LoginContextProvider 等 bean 定义冲突（先例 TestConfig 模式）
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 供应商管理 Service 测试")
class AgentProviderServiceImplTest {

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_1 = 1L;
    private static final String TEST_API_KEY = "sk-test-123456";

    @Autowired
    private AgentProviderService service;

    @Autowired
    private AgentProviderMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 建表（F1 阶段 1 H2 脚本 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_provider (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    api_format      VARCHAR(32) NOT NULL,
                    name            VARCHAR(100) NOT NULL,
                    base_url        VARCHAR(500),
                    api_key_cipher  CLOB,
                    enabled         SMALLINT NOT NULL DEFAULT 1,
                    remark          VARCHAR(500),
                    create_time     TIMESTAMP,
                    create_by       VARCHAR(64),
                    update_time     TIMESTAMP,
                    update_by       VARCHAR(64),
                    deleted         SMALLINT NOT NULL DEFAULT 0,
                    tenant_id       BIGINT NOT NULL DEFAULT 0,
                    version         BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_provider_name ON sw_agent_provider (tenant_id, name)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_provider");
        setLoginUser(TENANT_100, USER_1);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void setLoginUser(Long tenantId, Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setUsername("user_" + userId);
        LoginUserHolder.set(loginUser);
    }

    // ==================== 测试数据工厂 ====================

    private AgentProviderSaveReqDTO createReq(String name, String apiFormat, String apiKey) {
        AgentProviderSaveReqDTO req = new AgentProviderSaveReqDTO();
        req.setName(name);
        req.setApiFormat(apiFormat);
        req.setBaseUrl("http://localhost:1/v1");
        req.setApiKey(apiKey);
        req.setEnabled(true);
        return req;
    }

    // ==================== 用例 1/2：加密落库 ====================

    @Test
    @DisplayName("用例1: create 后 apiKeyCipher 非明文落库，且可被 decrypt 还原")
    void create_shouldEncryptApiKey() {
        Long id = service.create(createReq("provider-1", "OPENAI_CHAT", TEST_API_KEY));

        AgentProvider saved = mapper.selectById(id);
        assertThat(saved.getApiKeyCipher())
                .as("落库密文不得等于明文")
                .isNotEqualTo(TEST_API_KEY)
                .isNotNull();
        // 密文可还原（测试密钥走真实加密流程，不断言明文落库）
        assertThat(new AesGcmCipher(TEST_BASE64_KEY).decrypt(saved.getApiKeyCipher()))
                .isEqualTo(TEST_API_KEY);
    }

    @Test
    @DisplayName("用例2: create 时 apiKey 为空 → apiKeyCipher 落库为 null")
    void create_withoutApiKey_shouldStoreNullCipher() {
        Long id = service.create(createReq("local-provider", "OPENAI_CHAT", null));

        AgentProvider saved = mapper.selectById(id);
        assertThat(saved.getApiKeyCipher()).isNull();
    }

    // ==================== 用例 3：update 空 Key 不覆盖 ====================

    @Test
    @DisplayName("用例3: update 时 apiKey 为空不覆盖旧密文")
    void update_withEmptyApiKey_shouldKeepOldCipher() {
        Long id = service.create(createReq("provider-3", "OPENAI_CHAT", "old-key-abc"));

        AgentProviderSaveReqDTO updateReq = createReq("provider-3", "OPENAI_CHAT", "");
        service.update(id, updateReq);

        AgentProvider saved = mapper.selectById(id);
        assertThat(new AesGcmCipher(TEST_BASE64_KEY).decrypt(saved.getApiKeyCipher()))
                .as("空 Key 更新后旧密文应原样保留")
                .isEqualTo("old-key-abc");
    }

    // ==================== 用例 4：脱敏格式 ====================

    @Test
    @DisplayName("用例4: getById 返回 DTO 的 apiKeyMasked 格式正确（前2后2+****），DTO 不含 apiKeyCipher 字段")
    void getById_shouldReturnMaskedKey() {
        Long id = service.create(createReq("provider-4", "OPENAI_CHAT", TEST_API_KEY));

        AgentProviderDTO dto = service.getById(id);
        assertThat(dto.getApiKeyMasked())
                .as("脱敏格式应为前2后2 + ****")
                .isEqualTo("sk****56");
        // DTO 类不得含 apiKeyCipher 字段（编译期与序列化双重防线）
        boolean hasCipherField = Arrays.stream(AgentProviderDTO.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("apiKeyCipher"));
        assertThat(hasCipherField).as("AgentProviderDTO 不应声明 apiKeyCipher 字段").isFalse();
    }

    // ==================== 用例 5：name 唯一性 ====================

    @Test
    @DisplayName("用例5: 同租户重复 name 抛业务异常；跨租户同名允许")
    void create_duplicateName_sameTenantThrows_crossTenantAllowed() {
        service.create(createReq("openai-shared", "OPENAI_CHAT", TEST_API_KEY));

        // 同租户重复 → 业务异常，不落库
        assertThatThrownBy(() -> service.create(createReq("openai-shared", "OPENAI_CHAT", "other-key")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("已存在");

        // 跨租户同名 → 允许
        setLoginUser(TENANT_200, USER_1);
        Long crossTenantId = service.create(createReq("openai-shared", "OPENAI_CHAT", TEST_API_KEY));
        assertThat(crossTenantId).isNotNull();
        assertThat(mapper.selectById(crossTenantId).getTenantId()).isEqualTo(TENANT_200);
    }

    // ==================== 用例 6：apiFormat 白名单 ====================

    @Test
    @DisplayName("用例6: apiFormat 非法值拒绝，不落库")
    void create_withInvalidFormat_shouldThrow() {
        AgentProviderSaveReqDTO req = createReq("bad-format", "SPRING_AI", TEST_API_KEY);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不支持的 API 格式");
        assertThat(mapper.selectCount(null))
                .as("非法格式不应落库")
                .isZero();
    }

    // ==================== 用例 7：分页 + 关键字过滤 + 启用列表 ====================

    @Test
    @DisplayName("用例7: pageProviders 分页 + nameKeyword 过滤；listEnabled 仅返回启用供应商")
    void pageProviders_shouldFilterByKeyword() {
        service.create(createReq("alpha-provider", "OPENAI_CHAT", TEST_API_KEY));
        service.create(createReq("beta-provider", "OPENAI_CHAT", TEST_API_KEY));
        AgentProviderSaveReqDTO disabledReq = createReq("gamma-other", "ANTHROPIC", null);
        disabledReq.setEnabled(false);
        service.create(disabledReq);

        PageResult<AgentProviderDTO> filtered = service.pageProviders(new PageParam(), "provider");
        assertThat(filtered.getRecords()).hasSize(2);
        assertThat(filtered.getTotal()).isEqualTo(2);

        // 空关键字 → 全量
        PageResult<AgentProviderDTO> all = service.pageProviders(new PageParam(), null);
        assertThat(all.getRecords()).hasSize(3);
        assertThat(all.getTotal()).isEqualTo(3);

        // 分页参数生效
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(2);
        PageResult<AgentProviderDTO> paged = service.pageProviders(pageParam, null);
        assertThat(paged.getRecords()).hasSize(2);
        assertThat(paged.getTotal()).isEqualTo(3);
        assertThat(paged.getPageSize()).isEqualTo(2);

        // 启用列表：仅 enabled=1 的两条
        assertThat(service.listEnabled())
                .extracting(AgentProviderDTO::getName)
                .containsExactlyInAnyOrder("alpha-provider", "beta-provider");
    }

    // ==================== 用例 8：删除 ====================

    @Test
    @DisplayName("用例8: delete 后 getById 抛 NOT_FOUND")
    void delete_thenGetById_shouldThrowNotFound() {
        Long id = service.create(createReq("to-delete", "OPENAI_CHAT", TEST_API_KEY));
        service.delete(id);

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在");
    }

    // ==================== 组合测试配置 ====================

    /** 测试加密密钥：32 字节 "0123456789abcdef0123456789abcdef" 的 Base64 */
    static final String TEST_BASE64_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentprovider;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
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
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
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
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public TenantProperties tenantProperties() {
            TenantProperties props = new TenantProperties();
            props.setEnabled(true);
            return props;
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                TenantProperties tenantProperties,
                LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                CommonMetaObjectHandler metaObjectHandler,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.agent.entity");
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
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public AesGcmCipher aesGcmCipher() {
            return new AesGcmCipher(TEST_BASE64_KEY);
        }

        @Bean
        public AgentProviderService agentProviderService(AesGcmCipher aesGcmCipher) {
            // BaseServiceImpl 的 baseMapper 经 @Autowired 字段注入（容器后处理器对 @Bean 实例生效）
            return new AgentProviderServiceImpl(aesGcmCipher);
        }
    }
}
