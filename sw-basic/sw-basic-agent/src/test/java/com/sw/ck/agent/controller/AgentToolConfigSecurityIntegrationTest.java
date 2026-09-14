package com.sw.ck.agent.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.service.AgentToolConfigService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.filter.JwtAuthenticationFilter;
import com.sw.ck.security.handler.RestAccessDeniedHandler;
import com.sw.ck.security.handler.RestAuthenticationEntryPoint;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.jwt.JwtTokenProviderImpl;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.security.support.PermissionService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P48 / M07-F03-02 真实后端四拒绝场景集成测试（D197 审查 L8）。
 * <p>
 * 请求经过真实 Spring Method Security（{@code @PreAuthorize} + {@code @ss.hasPermi}）、
 * 真实 {@link AgentToolConfigController} / {@link AgentToolConfigServiceImpl}、
 * 真实 PermissionService（{@code "ss"} Bean）与 H2 数据库，非 mock Service。
 * </p>
 * <p>
 * 四种拒绝场景：内部/外部 × 未认证(401) / 已认证但缺 {@code agent:tool:manage}(403)。
 * 缺权用户（userId=4）权限集合为空，未持有 manage/view；另配 view-only（userId=5）
 * 验证「有 view 但无 manage 仍 403」，以及 superadmin（userId=6）正常写入作为对照。
 * 每个场景断言：身份权限 → 请求 → 401/403 状态 → 响应消息原文 → 目标数据行前后值。
 * </p>
 */
@SpringBootTest(
        classes = AgentToolConfigSecurityIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration,"
                        + "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
                "spring.ai.openai.api-key=test-dummy"
        }
)
@DisplayName("P48 工具权限四拒绝场景：真实 Security 链 + 真实 Service + H2 数据前后值")
class AgentToolConfigSecurityIntegrationTest {

    /** 缺权限普通用户（无 view 无 manage） */
    private static final Long USER_NONE = 4L;
    /** 仅 view 权限普通用户（无 manage） */
    private static final Long USER_VIEW_ONLY = 5L;
    /** superadmin（对照：正常写入） */
    private static final Long USER_SUPER = 6L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 建表 ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_tool_internal (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    description     VARCHAR(500) NOT NULL,
                    input_schema    CLOB,
                    bean_name       VARCHAR(100) NOT NULL,
                    method_name     VARCHAR(100) NOT NULL,
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
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_tool_external (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    description     VARCHAR(500) NOT NULL,
                    input_schema    CLOB,
                    url             VARCHAR(500) NOT NULL,
                    http_method     VARCHAR(10) NOT NULL DEFAULT 'POST',
                    timeout_seconds INT NOT NULL DEFAULT 30,
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
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_tool_internal");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_external");
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.generateToken(userId);
    }

    private String internalJson() {
        return """
                {"name":"perm_reject_internal","description":"内部工具","inputSchema":"{\\"type\\":\\"object\\"}","beanName":"calcBean","methodName":"execute","enabled":true}
                """;
    }

    private String externalJson() {
        return """
                {"name":"perm_reject_external","description":"外部工具","url":"http://127.0.0.1:1/weather","httpMethod":"POST","timeoutSeconds":30,"enabled":true}
                """;
    }

    // ==================== 场景 1：内部工具 × 未认证 → 401 ====================

    @Test
    @DisplayName("场景1: 内部工具 POST /agent/tool/internal 未认证 → 401，且不写入数据")
    void internal_unauth_shouldReturn401_andNotWrite() throws Exception {
        // 身份/权限输入：无 token（未认证）
        // 数据前值
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_internal WHERE deleted=0", Integer.class);

        MvcResult result = mockMvc.perform(post("/agent/tool/internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(internalJson()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // 响应状态 + 消息原文
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
        String msg = body.get("msg").asText();
        assertThat(msg).isNotBlank();

        // 数据后值：未写入
        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_internal WHERE deleted=0", Integer.class);
        assertThat(after).isEqualTo(before);

        System.out.println("[场景1] 内部工具 × 未认证: 401, msg=" + msg
                + ", 行数 " + before + "→" + after);
    }

    // ==================== 场景 2：外部工具 × 未认证 → 401 ====================

    @Test
    @DisplayName("场景2: 外部工具 POST /agent/tool/external 未认证 → 401，且不写入数据")
    void external_unauth_shouldReturn401_andNotWrite() throws Exception {
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);

        MvcResult result = mockMvc.perform(post("/agent/tool/external")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(externalJson()))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
        String msg = body.get("msg").asText();
        assertThat(msg).isNotBlank();

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);
        assertThat(after).isEqualTo(before);

        System.out.println("[场景2] 外部工具 × 未认证: 401, msg=" + msg
                + ", 行数 " + before + "→" + after);
    }

    // ==================== 场景 3：内部工具 × 缺 manage 权限 → 403 ====================

    @Test
    @DisplayName("场景3: 内部工具 POST /agent/tool/internal 已认证但缺 manage（无任何权限）→ 403，且不写入数据")
    void internal_lackManage_shouldReturn403_andNotWrite() throws Exception {
        // 身份/权限输入：userId=4，permissions 为空集合（无 agent:tool:manage / agent:tool:view）
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_internal WHERE deleted=0", Integer.class);

        MvcResult result = mockMvc.perform(post("/agent/tool/internal")
                        .header("Authorization", bearerToken(USER_NONE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(internalJson()))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        String msg = body.get("msg").asText();
        assertThat(msg).isNotBlank();

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_internal WHERE deleted=0", Integer.class);
        assertThat(after).isEqualTo(before);

        System.out.println("[场景3] 内部工具 × 缺manage(无权限): 403, msg=" + msg
                + ", 行数 " + before + "→" + after);
    }

    // ==================== 场景 4：外部工具 × 缺 manage 权限 → 403 ====================

    @Test
    @DisplayName("场景4: 外部工具 POST /agent/tool/external 已认证但缺 manage（无任何权限）→ 403，且不写入数据")
    void external_lackManage_shouldReturn403_andNotWrite() throws Exception {
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);

        MvcResult result = mockMvc.perform(post("/agent/tool/external")
                        .header("Authorization", bearerToken(USER_NONE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(externalJson()))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        String msg = body.get("msg").asText();
        assertThat(msg).isNotBlank();

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);
        assertThat(after).isEqualTo(before);

        System.out.println("[场景4] 外部工具 × 缺manage(无权限): 403, msg=" + msg
                + ", 行数 " + before + "→" + after);
    }

    // ==================== 补充：有 view 无 manage 仍 403（无 manage 缺权更严格证明） ====================

    @Test
    @DisplayName("补充: view-only 用户 POST /agent/tool/external 仍 403（拥有 view 但缺 manage）")
    void viewOnly_manage_shouldReturn403() throws Exception {
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);

        MvcResult result = mockMvc.perform(post("/agent/tool/external")
                        .header("Authorization", bearerToken(USER_VIEW_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(externalJson()))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);
        assertThat(after).isEqualTo(before);

        System.out.println("[补充] 外部工具 × view-only(缺manage): 403, msg="
                + body.get("msg").asText() + ", 行数 " + before + "→" + after);
    }

    // ==================== 对照：superadmin 正常写入（验证拒绝确实由缺权触发） ====================

    @Test
    @DisplayName("对照: superadmin POST /agent/tool/external → 200 且真实写入，详情可回读")
    void superAdmin_shouldWrite() throws Exception {
        MvcResult create = mockMvc.perform(post("/agent/tool/external")
                        .header("Authorization", bearerToken(USER_SUPER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(externalJson()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createBody = objectMapper.readTree(create.getResponse().getContentAsString());
        assertThat(createBody.get("code").asInt()).isZero();
        long id = createBody.get("data").asLong();
        assertThat(id).isPositive();

        MvcResult detail = mockMvc.perform(get("/agent/tool/external/" + id)
                        .header("Authorization", bearerToken(USER_SUPER)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detailBody = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertThat(detailBody.get("data").get("name").asText()).isEqualTo("perm_reject_external");
        assertThat(detailBody.get("data").get("timeoutSeconds").asInt()).isEqualTo(30);

        System.out.println("[对照] superadmin 外部工具写入: id=" + id + ", name="
                + detailBody.get("data").get("name").asText() + ", timeout="
                + detailBody.get("data").get("timeoutSeconds").asInt());
    }

    // ==================== L5：真实后端 timeout 两值（0 与 1）请求与数据 ====================

    @Test
    @DisplayName("L5: timeoutSeconds=0 → 后端归一化为 1（最小合法值）并持久化，响应 200，回读=1")
    void timeoutZero_shouldNormalizeTo1_andPersist() throws Exception {
        // 执行前持久化计数
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);

        // 精确端点 + 载荷：POST /agent/tool/external（superadmin，真实 Security 链）
        String payload = """
                {"name":"timeout_zero_it","description":"timeout 0","url":"http://127.0.0.1:1/x","httpMethod":"GET","timeoutSeconds":0,"enabled":true}
                """;
        MvcResult create = mockMvc.perform(post("/agent/tool/external")
                        .header("Authorization", bearerToken(USER_SUPER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createBody = objectMapper.readTree(create.getResponse().getContentAsString());
        assertThat(createBody.get("code").asInt()).isZero();
        long id = createBody.get("data").asLong();
        assertThat(id).isPositive();

        // 响应消息原文：R.ok() 的 msg="success"
        assertThat(createBody.get("msg").asText()).isEqualTo("success");

        // 执行后持久化查询：直接查库（同一 H2 会话）
        Integer timeoutStored = jdbcTemplate.queryForObject(
                "SELECT timeout_seconds FROM sw_agent_tool_external WHERE id=? AND deleted=0",
                Integer.class, id);
        assertThat(timeoutStored).isEqualTo(1); // 0 被归一化为 1（最小合法值）

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);
        assertThat(after).isEqualTo(before + 1);

        // 详情回读确认
        MvcResult detail = mockMvc.perform(get("/agent/tool/external/" + id)
                        .header("Authorization", bearerToken(USER_SUPER)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detailBody = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertThat(detailBody.get("data").get("timeoutSeconds").asInt()).isEqualTo(1);
        assertThat(detailBody.get("data").get("name").asText()).isEqualTo("timeout_zero_it");

        System.out.println("[L5] timeoutSeconds=0 → 归一化 1, id=" + id
                + ", 存储值=" + timeoutStored + ", 行数 " + before + "→" + after
                + ", 详情回读 timeoutSeconds=" + detailBody.get("data").get("timeoutSeconds").asInt());
    }

    @Test
    @DisplayName("L5: timeoutSeconds=1 → 原样持久化，响应 200，回读=1")
    void timeoutOne_shouldPersistAsIs() throws Exception {
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);

        String payload = """
                {"name":"timeout_one_it","description":"timeout 1","url":"http://127.0.0.1:1/y","httpMethod":"POST","timeoutSeconds":1,"enabled":true}
                """;
        MvcResult create = mockMvc.perform(post("/agent/tool/external")
                        .header("Authorization", bearerToken(USER_SUPER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createBody = objectMapper.readTree(create.getResponse().getContentAsString());
        assertThat(createBody.get("code").asInt()).isZero();
        long id = createBody.get("data").asLong();

        Integer timeoutStored = jdbcTemplate.queryForObject(
                "SELECT timeout_seconds FROM sw_agent_tool_external WHERE id=? AND deleted=0",
                Integer.class, id);
        assertThat(timeoutStored).isEqualTo(1); // 1 原样

        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_tool_external WHERE deleted=0", Integer.class);
        assertThat(after).isEqualTo(before + 1);

        System.out.println("[L5] timeoutSeconds=1 → 原样, id=" + id
                + ", 存储值=" + timeoutStored + ", 行数 " + before + "→" + after);
    }

    // ==================== 组合测试配置（真实 Security 链 + 真实 Service + H2） ====================

    @Configuration
    @EnableAutoConfiguration
    @EnableMethodSecurity
    @EnableWebSecurity
    @EnableTransactionManagement
    @MapperScan("com.sw.ck.agent.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .driverClassName("org.h2.Driver")
                    .url("jdbc:h2:mem:tool_perm_it;DB_CLOSE_DELAY=-1;MODE=LEGACY")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(new com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler(
                    new com.sw.ck.common.config.mybatis.tenant.TenantProperties(), loginContextProvider));
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
                DataSource dataSource, CommonMetaObjectHandler metaObjectHandler,
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
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public ObjectMapper objectMapper() {
            ObjectMapper om = new ObjectMapper();
            om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            om.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return om;
        }

        @Bean
        public AgentToolConfigService agentToolConfigService(
                AgentToolInternalConfigMapper internalMapper,
                AgentToolExternalConfigMapper externalMapper) {
            return new com.sw.ck.agent.service.impl.AgentToolConfigServiceImpl(externalMapper);
        }

        @Bean
        public AgentToolConfigController agentToolConfigController(
                AgentToolConfigService agentToolConfigService) {
            return new AgentToolConfigController(agentToolConfigService);
        }

        @Bean
        public LoginContextProvider loginContextProvider() {
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
                public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
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
        public JwtProperties jwtProperties() {
            JwtProperties props = new JwtProperties();
            props.setSecret("test-jwt-secret-at-least-256-bits-long-for-hs256-algorithm");
            props.setExpireSeconds(7200);
            props.setAccessExpireSeconds(900);
            props.setRefreshExpireSeconds(604800);
            return props;
        }

        @Bean
        public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) {
            return new JwtTokenProviderImpl(jwtProperties);
        }

        @Bean
        public SecurityProperties securityProperties() {
            SecurityProperties props = new SecurityProperties();
            props.setTokenHeader("Authorization");
            props.setTokenPrefix("Bearer ");
            props.setPermitUrls(List.of("/auth/login"));
            return props;
        }

        @Bean
        @SuppressWarnings({"unchecked", "rawtypes"})
        public LoginUserCacheService loginUserCacheService(JwtProperties jwtProperties) {
            RedisTemplate<String, Object> mockRedis = mock(RedisTemplate.class);
            ValueOperations<String, Object> mockOps = mock(ValueOperations.class);
            when(mockRedis.opsForValue()).thenReturn(mockOps);
            when(mockOps.get(anyString())).thenReturn(null);
            return new LoginUserCacheService(mockRedis, jwtProperties) {
                @Override
                public void cache(LoginUser loginUser) {
                    // no-op
                }

                @Override
                public void evict(Long userId) {
                    // no-op
                }
            };
        }

        @Bean
        @SuppressWarnings("unchecked")
        public LoginUserLoader loginUserLoader(
                UserDetailsProvider userDetailsProvider,
                LoginUserCacheService loginUserCacheService) {
            org.springframework.beans.factory.ObjectProvider<UserDetailsProvider> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(userDetailsProvider);
            return new LoginUserLoader(provider, loginUserCacheService);
        }

        /** 6=superadmin；5=仅 view；4=无任何权限 */
        @Bean
        public UserDetailsProvider userDetailsProvider() {
            Map<Long, LoginUser> users = new java.util.HashMap<>();
            LoginUser u4 = new LoginUser();
            u4.setUserId(4L);
            u4.setTenantId(0L);
            u4.setUsername("noperm");
            u4.setPermissions(List.of());
            u4.setRoles(List.of("normal"));
            u4.setSuperAdmin(false);
            users.put(4L, u4);
            LoginUser u5 = new LoginUser();
            u5.setUserId(5L);
            u5.setTenantId(0L);
            u5.setUsername("viewer");
            u5.setPermissions(List.of("agent:tool:view"));
            u5.setRoles(List.of("normal"));
            u5.setSuperAdmin(false);
            users.put(5L, u5);
            LoginUser u6 = new LoginUser();
            u6.setUserId(6L);
            u6.setTenantId(0L);
            u6.setUsername("super");
            u6.setPermissions(List.of());
            u6.setRoles(List.of("superadmin"));
            u6.setSuperAdmin(true);
            users.put(6L, u6);
            return new UserDetailsProvider() {
                @Override
                public LoginUser loadByUsername(String username) {
                    return null;
                }

                @Override
                public LoginUser loadByUserId(Long userId) {
                    return users.get(userId);
                }
            };
        }

        @Bean("ss")
        public PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http,
                                               JwtAuthenticationFilter jwtAuthenticationFilter,
                                               RestAccessDeniedHandler accessDeniedHandler,
                                               RestAuthenticationEntryPoint authenticationEntryPoint,
                                               PermissionService permissionService) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .exceptionHandling(e -> e
                            .accessDeniedHandler(accessDeniedHandler)
                            .authenticationEntryPoint(authenticationEntryPoint));
            return http.build();
        }

        @Bean
        public MockMvc mockMvc(WebApplicationContext context,
                               @Qualifier("springSecurityFilterChain") Filter springSecurityFilterChain) {
            return MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(springSecurityFilterChain)
                    .build();
        }

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter(
                JwtTokenProvider jwtTokenProvider,
                LoginUserLoader loginUserLoader,
                SecurityProperties securityProperties) {
            return new JwtAuthenticationFilter(jwtTokenProvider, loginUserLoader, securityProperties,
                    org.mockito.Mockito.mock(com.sw.ck.security.cache.LoginUserCacheService.class));
        }

        @Bean
        public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
            return new RestAccessDeniedHandler(objectMapper);
        }

        @Bean
        public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
            return new RestAuthenticationEntryPoint(objectMapper);
        }

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @RestControllerAdvice
        static class GlobalExceptionHandler {

            @ExceptionHandler(BaseException.class)
            public R<Void> handleBase(BaseException ex) {
                return R.fail(ex.getCode(), ex.getMessage());
            }
        }
    }
}
