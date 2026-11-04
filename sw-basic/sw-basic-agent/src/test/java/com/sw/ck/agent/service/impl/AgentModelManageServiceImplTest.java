package com.sw.ck.agent.service.impl;

import com.sw.ck.agent.dto.AgentModelDTO;
import com.sw.ck.agent.dto.AgentModelSaveReqDTO;
import com.sw.ck.agent.dto.AgentModelTestConnectionRespDTO;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.mapper.AgentModelMapper;
import com.sw.ck.agent.mapper.AgentProviderMapper;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.agent.service.AgentModelService;
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
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentModelManageServiceImpl} 测试（F1 阶段 1，D022-02/05）。
 * <p>
 * 原 {@code AgentModelConfigServiceImplTest} 的模型侧用例（CRUD/名称唯一/分页/多Key
 * 轮询字段落库）由本类承接。连通性测试语义已从 HTTP 探测（openai/ollama/other 协议
 * 根路径探测 + 4xx 可达判活）改为<b>真实最小调用</b>（maxOutputTokens=1、入参 "ping"），
 * 本类用 mock {@link AgentModelRuntimeService} + 桩 {@link ChatModel} 覆盖成功 /
 * 鉴权失败 / 模型缺失 / 未知 id 四类分支；纯 HTTP 探测类旧用例（other 协议根路径、
 * Authorization 头有无等）随旧协议体系一并废弃。
 * </p>
 * <p>
 * 策略：{@code @SpringBootTest} + H2（TestConfig 组合装配）+ {@code @Transactional}
 * （每用例回滚）；{@code AgentModelRuntimeService} 为 Mockito mock（模型 CRUD 用例不
 * 触发真实模型装配）。
 * </p>
 */
@SpringBootTest(
        classes = AgentModelManageServiceImplTest.TestConfig.class,
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
@DisplayName("Agent 模型管理 Service 测试")
class AgentModelManageServiceImplTest {

    private static final Long TENANT_100 = 100L;
    private static final Long USER_1 = 1L;

    @Autowired
    private AgentModelService service;

    @Autowired
    private AgentProviderMapper providerMapper;

    @Autowired
    private AgentModelMapper modelMapper;

    @Autowired
    private AgentModelRuntimeService modelRuntimeService;

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
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_model (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    provider_id     BIGINT NOT NULL,
                    name            VARCHAR(100) NOT NULL,
                    model_name      VARCHAR(100) NOT NULL,
                    capabilities    VARCHAR(50),
                    temperature     DECIMAL(4,2),
                    max_tokens      INT,
                    top_p           DECIMAL(4,2),
                    timeout_seconds INT NOT NULL DEFAULT 30,
                    retry_count     INT NOT NULL DEFAULT 0,
                    enabled         SMALLINT NOT NULL DEFAULT 1,
                    group_key       VARCHAR(100),
                    sort            INT NOT NULL DEFAULT 0,
                    locked_until    TIMESTAMP,
                    quota_cooldown_seconds INT NOT NULL DEFAULT 60,
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
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_model_name ON sw_agent_model (tenant_id, name)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_model_group ON sw_agent_model (tenant_id, group_key, sort)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_model");
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

    /** 插入一个 OPENAI_CHAT 供应商，返回其 id */
    private Long insertProvider(String name) {
        AgentProvider provider = new AgentProvider();
        provider.setApiFormat("OPENAI_CHAT");
        provider.setName(name);
        provider.setBaseUrl("http://localhost:1/v1");
        provider.setEnabled(true);
        providerMapper.insert(provider);
        return provider.getId();
    }

    private AgentModelSaveReqDTO createReq(Long providerId, String name) {
        AgentModelSaveReqDTO req = new AgentModelSaveReqDTO();
        req.setProviderId(providerId);
        req.setName(name);
        req.setModelName("gpt-4o");
        req.setCapabilities("CHAT");
        req.setTimeoutSeconds(30);
        req.setRetryCount(0);
        req.setEnabled(true);
        return req;
    }

    // ==================== 用例 1：providerId 必填 + 供应商存在性 ====================

    @Test
    @DisplayName("用例1: providerId 为空 / 供应商不存在 → 抛业务异常，不落库")
    void create_withInvalidProvider_shouldThrow() {
        assertThatThrownBy(() -> service.create(createReq(null, "no-provider-model")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("providerId 不能为空");

        assertThatThrownBy(() -> service.create(createReq(999999L, "ghost-provider-model")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("供应商不存在");

        assertThat(modelMapper.selectCount(null))
                .as("非法 providerId 不应落库")
                .isZero();
    }

    // ==================== 用例 2：name 唯一性 ====================

    @Test
    @DisplayName("用例2: 同租户重复 name 抛业务异常")
    void create_duplicateName_shouldThrow() {
        Long providerId = insertProvider("p-unique");
        service.create(createReq(providerId, "gpt-4o-main"));

        assertThatThrownBy(() -> service.create(createReq(providerId, "gpt-4o-main")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("已存在");
    }

    // ==================== 用例 3：CRUD 字段一致 + DTO 供应商信息透传 ====================

    @Test
    @DisplayName("用例3: create/getById 字段一致；DTO 透传 providerName/providerApiFormat")
    void createAndGet_shouldMapFields() {
        Long providerId = insertProvider("p-crud");
        Long id = service.create(createReq(providerId, "gpt-4o-crud"));

        AgentModelDTO dto = service.getById(id);
        assertThat(dto.getProviderId()).isEqualTo(providerId);
        assertThat(dto.getName()).isEqualTo("gpt-4o-crud");
        assertThat(dto.getModelName()).isEqualTo("gpt-4o");
        assertThat(dto.getCapabilities()).isEqualTo("CHAT");
        assertThat(dto.getTimeoutSeconds()).isEqualTo(30);
        assertThat(dto.getRetryCount()).isZero();
        assertThat(dto.getEnabled()).isTrue();
        assertThat(dto.getProviderName()).isEqualTo("p-crud");
        assertThat(dto.getProviderApiFormat()).isEqualTo("OPENAI_CHAT");
        assertThat(dto.getCreateTime()).isNotNull();
    }

    // ==================== 用例 4：多Key轮询字段落库 ====================

    @Test
    @DisplayName("用例4: create/update 携带 groupKey/sort/quotaCooldownSeconds → 正确落库回读；DTO 只读展示")
    void saveAndRead_withGroupKeyFields() {
        Long providerId = insertProvider("p-multikey");
        AgentModelSaveReqDTO req = createReq(providerId, "multikey-model");
        req.setGroupKey("g-pool");
        req.setSort(3);
        req.setQuotaCooldownSeconds(120);

        Long id = service.create(req);

        // 实体回读：新字段正确落库
        AgentModel saved = modelMapper.selectById(id);
        assertThat(saved.getGroupKey()).isEqualTo("g-pool");
        assertThat(saved.getSort()).isEqualTo(3);
        assertThat(saved.getQuotaCooldownSeconds()).isEqualTo(120);
        assertThat(saved.getLockedUntil()).isNull();

        // update 修改 groupKey/sort/quotaCooldownSeconds → 回读生效
        AgentModelSaveReqDTO updateReq = createReq(providerId, "multikey-model");
        updateReq.setGroupKey("g-pool-2");
        updateReq.setSort(7);
        updateReq.setQuotaCooldownSeconds(30);
        service.update(id, updateReq);

        AgentModelDTO dto = service.getById(id);
        assertThat(dto.getGroupKey()).isEqualTo("g-pool-2");
        assertThat(dto.getSort()).isEqualTo(7);
        assertThat(dto.getQuotaCooldownSeconds()).isEqualTo(30);
        assertThat(dto.getLockedUntil()).isNull();
    }

    // ==================== 用例 5：分页 + providerId/关键字过滤 ====================

    @Test
    @DisplayName("用例5: pageModels 分页 + providerId 过滤 + nameKeyword 过滤；listEnabled 按 capability 过滤")
    void pageModels_shouldFilter() {
        Long pA = insertProvider("p-page-a");
        Long pB = insertProvider("p-page-b");
        service.create(createReq(pA, "alpha-model"));
        service.create(createReq(pA, "beta-model"));
        AgentModelSaveReqDTO embeddingReq = createReq(pB, "gamma-embedding");
        embeddingReq.setCapabilities("EMBEDDING");
        embeddingReq.setEnabled(false);
        service.create(embeddingReq);

        // nameKeyword 过滤
        PageResult<AgentModelDTO> filtered = service.pageModels(new PageParam(), null, "model");
        assertThat(filtered.getRecords()).hasSize(2);
        assertThat(filtered.getTotal()).isEqualTo(2);

        // providerId 过滤
        PageResult<AgentModelDTO> byProvider = service.pageModels(new PageParam(), pB, null);
        assertThat(byProvider.getRecords()).hasSize(1);
        assertThat(byProvider.getRecords().get(0).getName()).isEqualTo("gamma-embedding");

        // 空条件全量 + 分页参数生效
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(2);
        PageResult<AgentModelDTO> paged = service.pageModels(pageParam, null, null);
        assertThat(paged.getRecords()).hasSize(2);
        assertThat(paged.getTotal()).isEqualTo(3);

        // listEnabled：仅 enabled=1；capability=EMBEDDING 过滤后为空
        assertThat(service.listEnabled(null)).hasSize(2);
        assertThat(service.listEnabled("EMBEDDING")).isEmpty();
    }

    @Test
    @DisplayName("阶段2: listEnabled 排除供应商停用的模型；DTO 标注 providerEnabled=false")
    void listEnabled_shouldExcludeProviderDisabledModels() {
        Long enabledProvider = insertProvider("p-enabled");
        Long disabledProvider = insertProvider("p-disabled");
        service.create(createReq(enabledProvider, "usable-model"));
        Long blockedId = service.create(createReq(disabledProvider, "blocked-model"));

        AgentProvider provider = providerMapper.selectById(disabledProvider);
        provider.setEnabled(false);
        providerMapper.updateById(provider);

        assertThat(service.listEnabled(null))
                .extracting(AgentModelDTO::getName)
                .contains("usable-model")
                .doesNotContain("blocked-model");
        assertThat(service.getById(blockedId).getProviderEnabled()).isFalse();
    }

    // ==================== 用例 6：删除 ====================

    @Test
    @DisplayName("用例6: delete 后 getById 抛 NOT_FOUND")
    void delete_thenGetById_shouldThrowNotFound() {
        Long providerId = insertProvider("p-del");
        Long id = service.create(createReq(providerId, "to-delete-model"));
        service.delete(id);

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 7-10：连通性测试（真实最小调用语义） ====================

    @Test
    @DisplayName("用例7: testConnection 模型真实回包 → success=true, message 非空, latencyMs>=0")
    void testConnection_success() {
        Long id = insertModelRow();
        stubAcquire(id, new OkChatModel("pong"));

        AgentModelTestConnectionRespDTO resp = service.testConnection(id);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getMessage()).isNotBlank();
        assertThat(resp.getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("用例8: testConnection 远端 401（鉴权失败≠网络不可达）→ success=false，可读原因含 401")
    void testConnection_401_shouldReturnReadableFailure() {
        Long id = insertModelRow();
        stubAcquire(id, new FailingChatModel(new HttpException(401, "unauthorized")));

        AgentModelTestConnectionRespDTO resp = service.testConnection(id);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).contains("401").contains("鉴权失败");
        assertThat(resp.getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("用例9: testConnection 远端 404（模型不存在）→ success=false，可读原因含 404")
    void testConnection_404_shouldReturnReadableFailure() {
        Long id = insertModelRow();
        stubAcquire(id, new FailingChatModel(new HttpException(404, "model not found")));

        AgentModelTestConnectionRespDTO resp = service.testConnection(id);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).contains("404").contains("模型不存在");
    }

    @Test
    @DisplayName("用例10: testConnection 目标不存在的 id → acquire 抛 NOT_FOUND 业务异常")
    void testConnection_unknownId_shouldThrow() {
        when(modelRuntimeService.acquire(999999L))
                .thenThrow(new BaseException(CommonErrorCode.NOT_FOUND, "模型不存在或已删除: 999999"));

        assertThatThrownBy(() -> service.testConnection(999999L))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 桩与辅助 ====================

    /** 直接经 mapper 插入一条模型行（绕过 service，供 testConnection 打桩使用） */
    private Long insertModelRow() {
        Long providerId = insertProvider("p-test-conn");
        AgentModel model = new AgentModel();
        model.setProviderId(providerId);
        model.setName("conn-model-" + System.nanoTime());
        model.setModelName("gpt-4o");
        model.setCapabilities("CHAT");
        model.setEnabled(true);
        modelMapper.insert(model);
        return model.getId();
    }

    /** 打桩运行时服务：acquire 指定模型 id 返回携带指定 ChatModel 的 ModelInstance */
    private void stubAcquire(Long id, ChatModel chatModel) {
        AgentModel model = modelMapper.selectById(id);
        AgentProvider provider = providerMapper.selectById(model.getProviderId());
        when(modelRuntimeService.acquire(id))
                .thenReturn(new AgentModelRuntimeService.ModelInstance(model, provider, chatModel));
    }

    /** 正常回包桩：返回固定文本 */
    static class OkChatModel implements ChatModel {
        private final String reply;

        OkChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    /** 失败桩：chat 抛指定异常 */
    static class FailingChatModel implements ChatModel {
        private final RuntimeException failure;

        FailingChatModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            throw failure;
        }
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
                    .url("jdbc:h2:mem:agentmodelmanage;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public AgentModelRuntimeService agentModelRuntimeService() {
            // 连通性测试打桩点：CRUD 用例不触发真实模型装配
            return mock(AgentModelRuntimeService.class);
        }

        @Bean
        public AgentModelService agentModelService(
                AgentProviderMapper agentProviderMapper,
                AgentModelRuntimeService modelRuntimeService) {
            // BaseServiceImpl 的 baseMapper 经 @Autowired 字段注入（容器后处理器对 @Bean 实例生效）
            return new AgentModelManageServiceImpl(agentProviderMapper, modelRuntimeService);
        }
    }
}
