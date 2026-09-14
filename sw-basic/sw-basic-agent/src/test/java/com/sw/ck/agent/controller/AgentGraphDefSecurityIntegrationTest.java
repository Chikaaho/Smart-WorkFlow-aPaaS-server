package com.sw.ck.agent.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeType;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.SpringBootTest;
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
 * {@code /agent/graph-defs} 系列端点的四类权限映射请求级安全回归证据（D152 补证 / 标准1）。
 * <p>
 * 证明 Prompt 配置的保存/发布/重载/执行在真实 Controller/Security 请求链下遵循权限门控：
 * <ul>
 *   <li>{@code POST /agent/graph-defs} — 创建图定义（{@code agent:model:manage}）</li>
 *   <li>{@code PUT /agent/graph-defs/{id}/graph} — 保存草稿含 Prompt 配置（{@code agent:model:manage}）</li>
 *   <li>{@code POST /agent/graph-defs/{id}/publish} — 发布图定义（{@code agent:model:manage}）</li>
 *   <li>{@code GET /agent/graph-defs/{id}} — 重载/详情（{@code agent:model:view}）</li>
 *   <li>{@code POST /agent/graph-defs/{id}/execute} — 执行已发布图（{@code agent:model:manage}）</li>
 * </ul>
 * <p>
 * 四类场景：
 * <ul>
 *   <li>授权访问 — 有权限 → 200 OK</li>
 *   <li>撤权拒绝 — 无权限 → HTTP 403</li>
 *   <li>未认证 — 无 JWT token → HTTP 401</li>
 *   <li>superadmin 豁免 — 超管跳过 hasPermi → 200 OK</li>
 * </ul>
 * <p>
 * 本测试类仅新增测试，不修改任何生产代码。
 */
@SpringBootTest(
        classes = AgentGraphDefSecurityIntegrationTest.TestConfig.class,
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
@DisplayName("图定义管理端点：四类权限映射安全回归（D152 补证 / 标准1）")
class AgentGraphDefSecurityIntegrationTest {

    /** 权限码常量 */
    private static final String PERM_MANAGE = "agent:model:manage";
    private static final String PERM_VIEW = "agent:model:view";

    // ==================== 注入 ====================

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
                CREATE TABLE IF NOT EXISTS sw_agent_graph_def (
                    id           BIGINT NOT NULL PRIMARY KEY,
                    graph_key    VARCHAR(100) NOT NULL,
                    name         VARCHAR(200) NOT NULL,
                    def_version  INT NOT NULL DEFAULT 1,
                    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                    graph_json   CLOB,
                    create_time  TIMESTAMP,
                    create_by    VARCHAR(64),
                    update_time  TIMESTAMP,
                    update_by    VARCHAR(64),
                    deleted      SMALLINT NOT NULL DEFAULT 0,
                    tenant_id    BIGINT NOT NULL DEFAULT 0,
                    version      BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_graph_key ON sw_agent_graph_def (tenant_id, graph_key)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_graph_tenant_deleted ON sw_agent_graph_def (tenant_id, deleted)");
    }

    // ==================== 前置/后置 ====================

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_graph_def");
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 辅助方法 ====================

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.generateToken(userId);
    }

    /** 创建一个 START→END 初始图并返回其 id（用超管创建）。 */
    private Long createDraftGraphAsCreator() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/agent/graph-defs")
                        .header("Authorization", bearerToken(3L))  // superAdmin
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"security-test-graph\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(createResult.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        return body.get("data").asLong();
    }

    /** 含 Prompt 配置的图 JSON（LLM 节点带 systemPrompt/userPromptTemplate） */
    private String graphJsonWithPromptConfig() {
        return """
                {"graphKey":"test_prompt_config","name":"test-prompt-config","version":1,
                 "elements":[
                   {"id":"start","kind":"node","type":"START","config":{},"style":{"x":100,"y":300}},
                   {"id":"llm","kind":"node","type":"LLM","config":{
                     "agentModelConfigId":1,
                     "systemPrompt":"你是专业翻译。",
                     "userPromptTemplate":"请翻译：{{input}}"
                   },"style":{"x":400,"y":300}},
                   {"id":"end","kind":"node","type":"END","config":{},"style":{"x":700,"y":300}},
                   {"id":"e1","kind":"edge","source":"start","target":"llm","config":{},"style":{}},
                   {"id":"e2","kind":"edge","source":"llm","target":"end","config":{},"style":{}}
                 ],"canvas":{}}
                """;
    }

    // ==================== 端点1：POST /agent/graph-defs（创建） ====================

    /** C1A: 授权访问 — 有 agent:model:manage → 200 OK + code=0 */
    @Test
    @DisplayName("C1A: 有 agent:model:manage 权限 → POST /agent/graph-defs → 200 OK")
    void create_withManagePermission_shouldReturn200() throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/graph-defs")
                        .header("Authorization", bearerToken(2L))  // user_manage
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"auth-test-graph\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").asLong()).isPositive();
    }

    /** C1B: 撤权拒绝 — 无 agent:model:manage → 403 */
    @Test
    @DisplayName("C1B: 无 agent:model:manage 权限 → POST /agent/graph-defs → 403")
    void create_withoutManagePermission_shouldReturn403() throws Exception {
        mockMvc.perform(post("/agent/graph-defs")
                        .header("Authorization", bearerToken(1L))  // user_none
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"forbidden-graph\"}"))
                .andExpect(status().isForbidden());
    }

    /** C1C: 未认证 — 无 token → 401 */
    @Test
    @DisplayName("C1C: 无 token → POST /agent/graph-defs → 401")
    void create_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/agent/graph-defs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"unauthenticated-graph\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** C1D: superadmin 豁免 — 无显式权限但超管 → 200 OK */
    @Test
    @DisplayName("C1D: superAdmin（无显式权限）→ POST /agent/graph-defs → 200 OK")
    void create_asSuperAdmin_shouldReturn200() throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/graph-defs")
                        .header("Authorization", bearerToken(3L))  // super_admin
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"superadmin-graph\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
    }

    // ==================== 端点2：PUT /agent/graph-defs/{id}/graph（保存草稿含Prompt） ====================

    /** C2A: 授权访问 — 有 agent:model:manage → 200 OK，保存含 Prompt 配置的图 */
    @Test
    @DisplayName("C2A: 有 agent:model:manage 权限 → PUT /agent/graph-defs/{id}/graph（含 Prompt）→ 200 OK")
    void saveDraft_withManagePermission_shouldReturn200() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(put("/agent/graph-defs/" + graphDefId + "/graph")
                        .header("Authorization", bearerToken(2L))  // user_manage
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphJsonWithPromptConfig()))
                .andExpect(status().isOk());
    }

    /** C2B: 撤权拒绝 — 无 agent:model:manage → 403 */
    @Test
    @DisplayName("C2B: 无 agent:model:manage 权限 → PUT /agent/graph-defs/{id}/graph → 403")
    void saveDraft_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(put("/agent/graph-defs/" + graphDefId + "/graph")
                        .header("Authorization", bearerToken(1L))  // user_none
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphJsonWithPromptConfig()))
                .andExpect(status().isForbidden());
    }

    /** C2C: 未认证 — 无 token → 401 */
    @Test
    @DisplayName("C2C: 无 token → PUT /agent/graph-defs/{id}/graph → 401")
    void saveDraft_withoutToken_shouldReturn401() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(put("/agent/graph-defs/" + graphDefId + "/graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphJsonWithPromptConfig()))
                .andExpect(status().isUnauthorized());
    }

    /** C2D: superadmin 豁免 → 200 OK */
    @Test
    @DisplayName("C2D: superAdmin → PUT /agent/graph-defs/{id}/graph → 200 OK")
    void saveDraft_asSuperAdmin_shouldReturn200() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(put("/agent/graph-defs/" + graphDefId + "/graph")
                        .header("Authorization", bearerToken(3L))  // super_admin
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphJsonWithPromptConfig()))
                .andExpect(status().isOk());
    }

    // ==================== 端点3：POST /agent/graph-defs/{id}/publish（发布） ====================

    /** C3A: 授权访问 — 有 agent:model:manage → 200 OK + status=PUBLISHED */
    @Test
    @DisplayName("C3A: 有 agent:model:manage 权限 → POST /agent/graph-defs/{id}/publish → 200 OK + PUBLISHED")
    void publish_withManagePermission_shouldReturn200() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        MvcResult result = mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/publish")
                        .header("Authorization", bearerToken(2L)))  // user_manage
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("status").asText()).isEqualTo("PUBLISHED");
    }

    /** C3B: 撤权拒绝 → 403 */
    @Test
    @DisplayName("C3B: 无 agent:model:manage 权限 → POST /agent/graph-defs/{id}/publish → 403")
    void publish_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/publish")
                        .header("Authorization", bearerToken(1L)))  // user_none
                .andExpect(status().isForbidden());
    }

    /** C3C: 未认证 → 401 */
    @Test
    @DisplayName("C3C: 无 token → POST /agent/graph-defs/{id}/publish → 401")
    void publish_withoutToken_shouldReturn401() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/publish"))
                .andExpect(status().isUnauthorized());
    }

    /** C3D: superadmin 豁免 → 200 OK */
    @Test
    @DisplayName("C3D: superAdmin → POST /agent/graph-defs/{id}/publish → 200 OK")
    void publish_asSuperAdmin_shouldReturn200() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/publish")
                        .header("Authorization", bearerToken(3L)))  // super_admin
                .andExpect(status().isOk());
    }

    // ==================== 端点4：GET /agent/graph-defs/{id}（重载/详情） ====================

    /** C4A: 授权访问 — 有 agent:model:view → 200 OK + 含 Prompt 配置 */
    @Test
    @DisplayName("C4A: 有 agent:model:view 权限 → GET /agent/graph-defs/{id}（重载）→ 200 OK + Prompt 配置完整保留")
    void getGraph_withViewPermission_shouldReturn200WithPromptConfig() throws Exception {
        // 先创建并发布含 Prompt 配置的图
        Long graphDefId = createDraftGraphAsCreator();
        mockMvc.perform(put("/agent/graph-defs/" + graphDefId + "/graph")
                        .header("Authorization", bearerToken(3L))  // superAdmin
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphJsonWithPromptConfig()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/publish")
                        .header("Authorization", bearerToken(3L)))
                .andExpect(status().isOk());

        // 用 view 权限用户重载
        MvcResult result = mockMvc.perform(get("/agent/graph-defs/" + graphDefId)
                        .header("Authorization", bearerToken(4L)))  // user_view
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();

        // 验证 Prompt 配置完整保留（标准1核心验证）
        JsonNode elements = body.get("data").get("elements");
        JsonNode llmNode = null;
        for (JsonNode elem : elements) {
            if ("LLM".equals(elem.get("type").asText())) {
                llmNode = elem;
                break;
            }
        }
        assertThat(llmNode).isNotNull();
        JsonNode config = llmNode.get("config");
        assertThat(config.get("systemPrompt").asText()).isEqualTo("你是专业翻译。");
        assertThat(config.get("userPromptTemplate").asText()).isEqualTo("请翻译：{{input}}");
    }

    /** C4B: 撤权拒绝 — 仅有 manage 但无 view → 403 */
    @Test
    @DisplayName("C4B: 无 agent:model:view 权限 → GET /agent/graph-defs/{id} → 403")
    void getGraph_withoutViewPermission_shouldReturn403() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        // user_manage 有 manage 但无 view
        mockMvc.perform(get("/agent/graph-defs/" + graphDefId)
                        .header("Authorization", bearerToken(2L)))  // user_manage
                .andExpect(status().isForbidden());
    }

    /** C4C: 未认证 → 401 */
    @Test
    @DisplayName("C4C: 无 token → GET /agent/graph-defs/{id} → 401")
    void getGraph_withoutToken_shouldReturn401() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(get("/agent/graph-defs/" + graphDefId))
                .andExpect(status().isUnauthorized());
    }

    /** C4D: superadmin 豁免 → 200 OK */
    @Test
    @DisplayName("C4D: superAdmin → GET /agent/graph-defs/{id} → 200 OK")
    void getGraph_asSuperAdmin_shouldReturn200() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(get("/agent/graph-defs/" + graphDefId)
                        .header("Authorization", bearerToken(3L)))  // super_admin
                .andExpect(status().isOk());
    }

    // ==================== 端点5：POST /agent/graph-defs/{id}/execute（执行） ====================

    /** C5A: 授权访问 — 有 agent:model:manage → 200 OK（DRAFT 图被门控，但鉴权通过） */
    @Test
    @DisplayName("C5A: 有 agent:model:manage 权限 → POST /agent/graph-defs/{id}/execute → 鉴权通过（200 or 业务失败）")
    void execute_withManagePermission_shouldPassAuth() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        // DRAFT 图执行会业务失败（PARAM_ERROR），但鉴权必须通过（HTTP 200）
        MvcResult result = mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/execute")
                        .header("Authorization", bearerToken(2L))  // user_manage
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"auth-test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        // 业务失败（DRAFT 图不可执行），但鉴权通过（HTTP 200，code != 0）
        assertThat(body.get("code").asInt()).isNotZero();
    }

    /** C5B: 撤权拒绝 → 403 */
    @Test
    @DisplayName("C5B: 无 agent:model:manage 权限 → POST /agent/graph-defs/{id}/execute → 403")
    void execute_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/execute")
                        .header("Authorization", bearerToken(1L))  // user_none
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"forbidden\"}"))
                .andExpect(status().isForbidden());
    }

    /** C5C: 未认证 → 401 */
    @Test
    @DisplayName("C5C: 无 token → POST /agent/graph-defs/{id}/execute → 401")
    void execute_withoutToken_shouldReturn401() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"unauthenticated\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** C5D: superadmin 豁免 → 200 OK */
    @Test
    @DisplayName("C5D: superAdmin → POST /agent/graph-defs/{id}/execute → 200 OK（鉴权通过）")
    void execute_asSuperAdmin_shouldPassAuth() throws Exception {
        Long graphDefId = createDraftGraphAsCreator();

        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/execute")
                        .header("Authorization", bearerToken(3L))  // super_admin
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"superadmin-test\"}"))
                .andExpect(status().isOk());
    }

    // ==================== 测试配置 ====================

    static class StubUserDetailsProvider implements UserDetailsProvider {

        private final Map<Long, LoginUser> users;

        StubUserDetailsProvider(Map<Long, LoginUser> users) {
            this.users = users;
        }

        @Override
        public LoginUser loadByUsername(String username) {
            return null;
        }

        @Override
        public LoginUser loadByUserId(Long userId) {
            return users.get(userId);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        // ==================== 数据源 + MyBatis-Plus ====================

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentdefsec;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(new com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler(
                    new com.sw.ck.common.config.mybatis.tenant.TenantProperties(), loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
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

        // ==================== 业务 Bean ====================

        @Bean
        public com.sw.ck.agent.service.AgentGraphDefService agentGraphDefService(
                ObjectMapper objectMapper) {
            return new com.sw.ck.agent.service.impl.AgentGraphDefServiceImpl(objectMapper);
        }

        @Bean
        public ChatModelFactory chatModelFactory() {
            return mock(ChatModelFactory.class);
        }

        @Bean
        public AgentToolCallbackFactory agentToolCallbackFactory() {
            return mock(AgentToolCallbackFactory.class);
        }

        /** 测试 AES 密钥 */
        private static final String TEST_CIPHER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

        @Bean
        public AesGcmCipher aesGcmCipher() {
            return new AesGcmCipher(TEST_CIPHER_KEY);
        }

        @Bean
        public AgentGraphExecutionService agentGraphExecutionService(
                ObjectMapper objectMapper,
                com.sw.ck.agent.mapper.AgentModelConfigMapper modelConfigMapper,
                com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper internalToolMapper,
                com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper externalToolMapper,
                com.sw.ck.agent.mapper.AgentGraphExecutionMapper executionMapper,
                com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper executionNodeMapper,
                ChatModelFactory chatModelFactory,
                AesGcmCipher aesGcmCipher,
                LoginContextProvider loginContextProvider,
                com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            return new com.sw.ck.agent.service.impl.AgentGraphExecutionServiceImpl(
                    objectMapper, modelConfigMapper, internalToolMapper, externalToolMapper,
                    executionMapper, executionNodeMapper, chatModelFactory, aesGcmCipher,
                    loginContextProvider, deptScopeProvider);
        }

        @Bean
        public com.sw.ck.common.datascope.DeptScopeProvider testDeptScopeProvider() {
            return deptId -> List.of();
        }

        @Bean
        public AgentGraphDefController agentGraphDefController(
                com.sw.ck.agent.service.AgentGraphDefService agentGraphDefService,
                AgentGraphExecutionService agentGraphExecutionService) {
            return new AgentGraphDefController(agentGraphDefService, agentGraphExecutionService);
        }

        // ==================== JWT / 认证 ====================

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
                }

                @Override
                public void evict(Long userId) {
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

        /**
         * 四个测试用户：
         * 1 = 无任何权限、非超管（用于 403 场景）
         * 2 = 仅有 agent:model:manage 权限（用于 saveDraft/publish/execute 授权访问）
         * 3 = superAdmin，权限空（用于 superadmin 豁免场景 + 创建测试数据）
         * 4 = 拥有 agent:model:view 权限（用于 getGraph 重载授权访问）
         */
        @Bean
        public UserDetailsProvider userDetailsProvider() {
            Map<Long, LoginUser> users = new java.util.HashMap<>();

            // 用户1: 无权限
            LoginUser u1 = new LoginUser();
            u1.setUserId(1L);
            u1.setTenantId(100L);
            u1.setUsername("user_none");
            u1.setPermissions(List.of());
            u1.setSuperAdmin(false);
            users.put(1L, u1);

            // 用户2: 仅 manage
            LoginUser u2 = new LoginUser();
            u2.setUserId(2L);
            u2.setTenantId(100L);
            u2.setUsername("user_manage");
            u2.setPermissions(List.of("agent:model:manage"));
            u2.setSuperAdmin(false);
            users.put(2L, u2);

            // 用户3: superAdmin
            LoginUser u3 = new LoginUser();
            u3.setUserId(3L);
            u3.setTenantId(100L);
            u3.setUsername("super_admin");
            u3.setPermissions(List.of());
            u3.setSuperAdmin(true);
            users.put(3L, u3);

            // 用户4: 有 view 权限
            LoginUser u4 = new LoginUser();
            u4.setUserId(4L);
            u4.setTenantId(100L);
            u4.setUsername("user_view");
            u4.setPermissions(List.of("agent:model:view"));
            u4.setSuperAdmin(false);
            users.put(4L, u4);

            return new StubUserDetailsProvider(users);
        }

        // ==================== 安全链 Bean ====================

        @Bean("ss")
        public PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
            return new RestAuthenticationEntryPoint(objectMapper);
        }

        @Bean
        public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
            return new RestAccessDeniedHandler(objectMapper);
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
        public UserDetailsService noopUserDetailsService() {
            return username -> {
                throw new UsernameNotFoundException(
                        "本系统认证不经过 UserDetailsService：" + username);
            };
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
        public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                       JwtAuthenticationFilter jwtAuthenticationFilter,
                                                       RestAuthenticationEntryPoint authenticationEntryPoint,
                                                       RestAccessDeniedHandler accessDeniedHandler) throws Exception {
            http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler))
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        public MockMvc mockMvc(WebApplicationContext context,
                               @Qualifier("springSecurityFilterChain") Filter springSecurityFilterChain) {
            return MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(springSecurityFilterChain)
                    .build();
        }

        // ==================== 业务异常处理器 ====================

        @RestControllerAdvice
        static class TestBusinessExceptionAdvice {

            @ExceptionHandler(BaseException.class)
            public R<Void> handleBaseException(BaseException ex) {
                return R.fail(ex.getCode(), ex.getMessage());
            }
        }

        @Bean
        public TestBusinessExceptionAdvice testBusinessExceptionAdvice() {
            return new TestBusinessExceptionAdvice();
        }
    }
}
