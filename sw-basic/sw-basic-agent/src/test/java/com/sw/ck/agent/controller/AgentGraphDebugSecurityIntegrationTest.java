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
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.service.AgentGraphDebugService;
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
 * 图调试会话端点四类权限映射 + 真实业务流安全回归（D176 / M07-F02-04）。
 * <p>
 * 请求经过真实 Spring Method Security（{@code @PreAuthorize} + {@code @ss.hasPermi}）、
 * 真实 {@link AgentGraphDebugController} / {@link AgentGraphDefController}、
 * 真实 PermissionService（{@code "ss"} Bean）与 H2 数据库。
 * </p>
 */
@SpringBootTest(
        classes = AgentGraphDebugSecurityIntegrationTest.TestConfig.class,
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
@DisplayName("图调试会话端点：四类权限映射 + 业务流安全回归（D176）")
class AgentGraphDebugSecurityIntegrationTest {

    private static final String PERM_VIEW = "agent:model:view";
    private static final String PERM_MANAGE = "agent:model:manage";

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
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_model_config (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    protocol_type   VARCHAR(32) NOT NULL,
                    base_url        VARCHAR(500) NOT NULL,
                    model_name      VARCHAR(100) NOT NULL,
                    api_key_cipher  CLOB,
                    temperature     DECIMAL(4,2),
                    max_tokens      INT,
                    top_p           DECIMAL(4,2),
                    timeout_seconds INT NOT NULL DEFAULT 30,
                    retry_count     INT NOT NULL DEFAULT 0,
                    enabled         SMALLINT NOT NULL DEFAULT 1,
                    remark          VARCHAR(500),
                    create_time     TIMESTAMP,
                    create_by       VARCHAR(64),
                    update_time     TIMESTAMP,
                    update_by       VARCHAR(64),
                    deleted         SMALLINT NOT NULL DEFAULT 0,
                    tenant_id       BIGINT NOT NULL DEFAULT 0,
                    version         BIGINT NOT NULL DEFAULT 0,
                    group_key       VARCHAR(100),
                    sort            INT NOT NULL DEFAULT 0,
                    locked_until    TIMESTAMP,
                    quota_cooldown_seconds INT NOT NULL DEFAULT 60
                )
                """);
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
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_debug_session (
                    id                BIGINT      NOT NULL PRIMARY KEY,
                    graph_def_id      BIGINT      NOT NULL,
                    graph_def_version INT         NOT NULL,
                    graph_json        CLOB,
                    status            VARCHAR(20) NOT NULL,
                    input             CLOB,
                    breakpoints       CLOB,
                    state_json        CLOB,
                    result_text       CLOB,
                    error_category    VARCHAR(50),
                    error_message     CLOB,
                    latency_ms        BIGINT,
                    expires_at        TIMESTAMP,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    create_time       TIMESTAMP   NOT NULL,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT    NOT NULL DEFAULT 0,
                    tenant_id         BIGINT      NOT NULL DEFAULT 0,
                    version           BIGINT      NOT NULL DEFAULT 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_debug_node (
                    id                BIGINT       NOT NULL PRIMARY KEY,
                    debug_session_id  BIGINT       NOT NULL,
                    node_seq          INT          NOT NULL,
                    branch_id         VARCHAR(64)  NOT NULL,
                    node_id           VARCHAR(100) NOT NULL,
                    node_type         VARCHAR(20)  NOT NULL,
                    node_latency_ms   BIGINT,
                    variable_snapshot CLOB,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    create_time       TIMESTAMP    NOT NULL,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT     NOT NULL DEFAULT 0,
                    tenant_id         BIGINT       NOT NULL DEFAULT 0,
                    version           BIGINT       NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_gexec_debug_graph ON sw_agent_graph_debug_session (graph_def_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_genode_debug ON sw_agent_graph_debug_node (debug_session_id, node_seq, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_graph_debug_node");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_debug_session");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_def");
        jdbcTemplate.update("DELETE FROM sw_agent_model_config");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_internal");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_external");
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 辅助 ====================

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.generateToken(userId);
    }

    private String creatorToken() {
        return bearerToken(3L);
    }

    /** 用超管创建并发布 START->END 图，返回 graphDefId。 */
    private Long createPublishedGraph() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/agent/graph-defs")
                        .header("Authorization", creatorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"debug-sec-graph\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(createResult.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        long graphDefId = body.get("data").asLong();
        mockMvc.perform(post("/agent/graph-defs/" + graphDefId + "/publish")
                        .header("Authorization", creatorToken()))
                .andExpect(status().isOk());
        return graphDefId;
    }

    /** 用指定 token 创建调试会话，返回 sessionId。 */
    private long createSessionAndGetId(String token, Long graphDefId) throws Exception {
        String payload = "{\"graphDefId\":" + graphDefId + ",\"input\":\"sec-debug-input\"}";
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        return body.get("data").get("id").asLong();
    }

    // ==================== 端点1：POST /agent/graph-debug-sessions（创建） ====================

    @Test
    @DisplayName("E1A: 有 agent:model:manage 权限 -> POST /agent/graph-debug-sessions -> 200 + PAUSED + expiresAt + nextNodeId")
    void create_withManagePermission_shouldReturnPausedSession() throws Exception {
        Long graphDefId = createPublishedGraph();
        String payload = "{\"graphDefId\":" + graphDefId + ",\"input\":\"hello-debug\"}";
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions")
                        .header("Authorization", bearerToken(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        JsonNode data = body.get("data");
        assertThat(data.get("status").asText()).isEqualTo("PAUSED");
        assertThat(data.get("expiresAt").asText()).isNotBlank();
        assertThat(data.get("nextNodeId").asText()).isEqualTo("node_start");
        assertThat(data.get("id").asLong()).isPositive();
    }

    @Test
    @DisplayName("E1B: 无 manage 权限 -> POST /agent/graph-debug-sessions -> 403")
    void create_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createPublishedGraph();
        String payload = "{\"graphDefId\":" + graphDefId + ",\"input\":\"hello\"}";
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions")
                        .header("Authorization", bearerToken(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("E1C: 无 token -> POST /agent/graph-debug-sessions -> 401")
    void create_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphDefId\":1,\"input\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    @Test
    @DisplayName("E1D: superadmin 旁路 -> POST /agent/graph-debug-sessions -> 200")
    void create_superAdminBypass_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        String payload = "{\"graphDefId\":" + graphDefId + ",\"input\":\"super-input\"}";
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions")
                        .header("Authorization", bearerToken(5L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("status").asText()).isEqualTo("PAUSED");
    }

    // ==================== 端点2：GET /agent/graph-debug-sessions/{id}（详情） ====================

    @Test
    @DisplayName("E2A: 有 view 权限 -> GET /agent/graph-debug-sessions/{id} -> 200 + PAUSED 明细")
    void detail_withViewPermission_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId)
                        .header("Authorization", bearerToken(4L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("status").asText()).isEqualTo("PAUSED");
        assertThat(body.get("data").get("nextNodeId").asText()).isEqualTo("node_start");
    }

    @Test
    @DisplayName("E2B: 无 view 权限 -> GET /agent/graph-debug-sessions/{id} -> 403")
    void detail_withoutViewPermission_shouldReturn403() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId)
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("E2C: 无 token -> GET /agent/graph-debug-sessions/{id} -> 401")
    void detail_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/1"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    @Test
    @DisplayName("E2D: superadmin 旁路 -> GET /agent/graph-debug-sessions/{id} -> 200")
    void detail_superAdminBypass_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId)
                        .header("Authorization", bearerToken(5L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
    }

    // ==================== 端点3：GET /agent/graph-debug-sessions/{id}/nodes（轨迹） ====================

    @Test
    @DisplayName("E3A: 有 view 权限 -> GET /agent/graph-debug-sessions/{id}/nodes -> 200")
    void nodes_withViewPermission_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId + "/nodes")
                        .header("Authorization", bearerToken(4L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").isArray()).isTrue();
    }

    @Test
    @DisplayName("E3B: 无 view 权限 -> GET /agent/graph-debug-sessions/{id}/nodes -> 403")
    void nodes_withoutViewPermission_shouldReturn403() throws Exception {
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/1/nodes")
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("E3C: 无 token -> GET /agent/graph-debug-sessions/{id}/nodes -> 401")
    void nodes_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/1/nodes"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    @Test
    @DisplayName("E3D: superadmin 旁路 -> GET /agent/graph-debug-sessions/{id}/nodes -> 200")
    void nodes_superAdminBypass_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId + "/nodes")
                        .header("Authorization", bearerToken(5L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
    }

    // ==================== 端点4：POST /agent/graph-debug-sessions/{id}/step（单步） ====================

    @Test
    @DisplayName("E4A: 有 manage 权限 -> POST step -> 200 + traceCount +1")
    void step_withManagePermission_shouldAdvanceOneTrace() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(bearerToken(2L), graphDefId);
        // step START
        MvcResult stepResult = mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/step")
                        .header("Authorization", bearerToken(2L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode stepBody = objectMapper.readTree(stepResult.getResponse().getContentAsString());
        assertThat(stepBody.get("code").asInt()).isZero();
        assertThat(stepBody.get("data").get("traceCount").asInt()).isEqualTo(1);
        // 验证 nodes +1
        MvcResult nodesResult = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId + "/nodes")
                        .header("Authorization", bearerToken(4L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode nodesBody = objectMapper.readTree(nodesResult.getResponse().getContentAsString());
        assertThat(nodesBody.get("data").size()).isEqualTo(1);
        assertThat(nodesBody.get("data").get(0).get("nodeType").asText()).isEqualTo("START");
    }

    @Test
    @DisplayName("E4B: 无 manage 权限 -> POST step -> 403")
    void step_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/step")
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("E4C: 无 token -> POST step -> 401")
    void step_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/1/step"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    @Test
    @DisplayName("E4D: superadmin 旁路 -> POST step -> 200")
    void step_superAdminBypass_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/step")
                        .header("Authorization", bearerToken(5L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
    }

    // ==================== 端点5：POST /agent/graph-debug-sessions/{id}/continue ====================

    @Test
    @DisplayName("E5A: 有 manage 权限 -> POST continue -> 200")
    void continue_withManagePermission_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(bearerToken(2L), graphDefId);
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/continue")
                        .header("Authorization", bearerToken(2L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        // START->END 无断点，continue 应跑到 COMPLETED
        assertThat(body.get("data").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("E5B: 无 manage 权限 -> POST continue -> 403")
    void continue_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/continue")
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("E5C: 无 token -> POST continue -> 401")
    void continue_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/1/continue"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    // ==================== 端点6：POST /agent/graph-debug-sessions/{id}/stop ====================

    @Test
    @DisplayName("E6A: 有 manage 权限 -> POST stop -> 200 STOPPED")
    void stop_withManagePermission_shouldReturnStopped() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(bearerToken(2L), graphDefId);
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/stop")
                        .header("Authorization", bearerToken(2L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("status").asText()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("E6B: 无 manage 权限 -> POST stop -> 403")
    void stop_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/stop")
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("E6C: 无 token -> POST stop -> 401")
    void stop_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/graph-debug-sessions/1/stop"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    // ==================== 端点7：PUT /agent/graph-debug-sessions/{id}/breakpoints ====================

    @Test
    @DisplayName("E7A: 有 manage 权限 -> PUT breakpoints -> 200")
    void breakpoints_withManagePermission_shouldReturn200() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(bearerToken(2L), graphDefId);
        MvcResult result = mockMvc.perform(put("/agent/graph-debug-sessions/" + sessionId + "/breakpoints")
                        .header("Authorization", bearerToken(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"breakpoints\":[\"node_end\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("E7B: 无 manage 权限 -> PUT breakpoints -> 403")
    void breakpoints_withoutManagePermission_shouldReturn403() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        MvcResult result = mockMvc.perform(put("/agent/graph-debug-sessions/" + sessionId + "/breakpoints")
                        .header("Authorization", bearerToken(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"breakpoints\":[]}"))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("E7C: 无 token -> PUT breakpoints -> 401")
    void breakpoints_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(put("/agent/graph-debug-sessions/1/breakpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"breakpoints\":[]}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    // ==================== 业务流证据 ====================

    @Test
    @DisplayName("BF1: POST step 推进后 GET nodes count +1（含业务真实落库）")
    void businessFlow_stepAdvancesTraceCount() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(bearerToken(2L), graphDefId);
        // 初始 nodes 为 0
        MvcResult before = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId + "/nodes")
                        .header("Authorization", bearerToken(4L)))
                .andExpect(status().isOk())
                .andReturn();
        int beforeCount = objectMapper.readTree(before.getResponse().getContentAsString()).get("data").size();
        assertThat(beforeCount).isZero();
        // step 一次
        mockMvc.perform(post("/agent/graph-debug-sessions/" + sessionId + "/step")
                        .header("Authorization", bearerToken(2L)))
                .andExpect(status().isOk());
        // 再次查询 nodes +1
        MvcResult after = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId + "/nodes")
                        .header("Authorization", bearerToken(4L)))
                .andExpect(status().isOk())
                .andReturn();
        int afterCount = objectMapper.readTree(after.getResponse().getContentAsString()).get("data").size();
        assertThat(afterCount).isEqualTo(beforeCount + 1);
    }

    @Test
    @DisplayName("BF2: PUT breakpoints 非法 nodeId -> 400")
    void businessFlow_invalidBreakpoint_shouldReturn400() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(bearerToken(2L), graphDefId);
        MvcResult result = mockMvc.perform(put("/agent/graph-debug-sessions/" + sessionId + "/breakpoints")
                        .header("Authorization", bearerToken(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"breakpoints\":[\"not_exist_node\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("BF3: 跨租户访问 -> 404 隔离（租户 200 用户无法访问租户 100 会话）")
    void businessFlow_crossTenant_shouldReturn404() throws Exception {
        Long graphDefId = createPublishedGraph();
        long sessionId = createSessionAndGetId(creatorToken(), graphDefId);
        // user 7 = tenant 200, 有 view 权限
        MvcResult result = mockMvc.perform(get("/agent/graph-debug-sessions/" + sessionId)
                        .header("Authorization", bearerToken(7L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(404);
    }

    // ==================== TestConfig ====================

    static class StubUserDetailsProvider implements UserDetailsProvider {
        private final Map<Long, LoginUser> users;
        StubUserDetailsProvider(Map<Long, LoginUser> users) { this.users = users; }
        @Override public LoginUser loadByUsername(String username) { return null; }
        @Override public LoginUser loadByUserId(Long userId) { return users.get(userId); }
    }

    @Configuration
    @EnableAutoConfiguration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentdebugsec;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }

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
                DataSource dataSource, CommonMetaObjectHandler metaObjectHandler, MybatisPlusInterceptor interceptor) throws Exception {
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
        public ObjectMapper objectMapper() { ObjectMapper om = new ObjectMapper(); om.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()); om.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); return om; }

        @Bean
        public com.sw.ck.agent.service.AgentGraphDefService agentGraphDefService(ObjectMapper objectMapper) {
            return new com.sw.ck.agent.service.impl.AgentGraphDefServiceImpl(objectMapper);
        }

        private static final String TEST_CIPHER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

        @Bean
        public ChatModelFactory chatModelFactory() { return mock(ChatModelFactory.class); }

        @Bean
        public AgentToolCallbackFactory agentToolCallbackFactory() { return mock(AgentToolCallbackFactory.class); }

        @Bean
        public AesGcmCipher aesGcmCipher() { return new AesGcmCipher(TEST_CIPHER_KEY); }

        @Bean
        public AgentGraphDebugService agentGraphDebugService(
                ObjectMapper objectMapper,
                com.sw.ck.agent.mapper.AgentGraphDefMapper graphDefMapper,
                com.sw.ck.agent.mapper.AgentGraphDebugSessionMapper sessionMapper,
                com.sw.ck.agent.mapper.AgentGraphDebugNodeMapper debugNodeMapper,
                com.sw.ck.agent.mapper.AgentModelConfigMapper modelConfigMapper,
                com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper internalToolMapper,
                com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper externalToolMapper,
                ChatModelFactory chatModelFactory,
                AesGcmCipher aesGcmCipher,
                LoginContextProvider loginContextProvider,
                com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            return new com.sw.ck.agent.service.impl.AgentGraphDebugServiceImpl(
                    objectMapper, graphDefMapper, sessionMapper, debugNodeMapper,
                    modelConfigMapper, internalToolMapper, externalToolMapper,
                    chatModelFactory, aesGcmCipher, loginContextProvider, deptScopeProvider);
        }

        @Bean
        public com.sw.ck.common.datascope.DeptScopeProvider testDeptScopeProvider() { return deptId -> List.of(); }

        @Bean
        public AgentGraphDebugController agentGraphDebugController(AgentGraphDebugService agentGraphDebugService) {
            return new AgentGraphDebugController(agentGraphDebugService);
        }

        @Bean
        public AgentGraphDefController agentGraphDefController(
                com.sw.ck.agent.service.AgentGraphDefService agentGraphDefService,
                com.sw.ck.agent.service.AgentGraphExecutionService agentGraphExecutionService) {
            return new AgentGraphDefController(agentGraphDefService, agentGraphExecutionService);
        }

        @Bean
        public com.sw.ck.agent.service.AgentGraphExecutionService agentGraphExecutionService(
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
        public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) { return new JwtTokenProviderImpl(jwtProperties); }

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
                @Override public void cache(LoginUser loginUser) {}
                @Override public void evict(Long userId) {}
            };
        }

        @Bean
        @SuppressWarnings("unchecked")
        public LoginUserLoader loginUserLoader(UserDetailsProvider userDetailsProvider, LoginUserCacheService loginUserCacheService) {
            org.springframework.beans.factory.ObjectProvider<UserDetailsProvider> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(userDetailsProvider);
            return new LoginUserLoader(provider, loginUserCacheService);
        }

        @Bean
        public UserDetailsProvider userDetailsProvider() {
            Map<Long, LoginUser> users = new java.util.HashMap<>();
            LoginUser u1 = new LoginUser(); u1.setUserId(1L); u1.setTenantId(100L); u1.setUsername("user_none"); u1.setPermissions(List.of()); u1.setSuperAdmin(false); users.put(1L, u1);
            LoginUser u2 = new LoginUser(); u2.setUserId(2L); u2.setTenantId(100L); u2.setUsername("user_manage"); u2.setPermissions(List.of("agent:model:manage")); u2.setSuperAdmin(false); users.put(2L, u2);
            LoginUser u3 = new LoginUser(); u3.setUserId(3L); u3.setTenantId(100L); u3.setUsername("super_admin"); u3.setPermissions(List.of()); u3.setSuperAdmin(true); users.put(3L, u3);
            LoginUser u4 = new LoginUser(); u4.setUserId(4L); u4.setTenantId(100L); u4.setUsername("user_view"); u4.setPermissions(List.of("agent:model:view")); u4.setSuperAdmin(false); users.put(4L, u4);
            LoginUser u5 = new LoginUser(); u5.setUserId(5L); u5.setTenantId(100L); u5.setUsername("super_admin_2"); u5.setPermissions(List.of()); u5.setSuperAdmin(true); users.put(5L, u5);
            LoginUser u6 = new LoginUser(); u6.setUserId(6L); u6.setTenantId(100L); u6.setUsername("user_other_perm"); u6.setPermissions(List.of("system:user:list")); u6.setSuperAdmin(false); users.put(6L, u6);
            LoginUser u7 = new LoginUser(); u7.setUserId(7L); u7.setTenantId(200L); u7.setUsername("user_cross_tenant"); u7.setPermissions(List.of("agent:model:view")); u7.setSuperAdmin(false); users.put(7L, u7);
            return new StubUserDetailsProvider(users);
        }

        @Bean("ss")
        public PermissionService permissionService() { return new PermissionService(); }

        @Bean
        public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) { return new RestAuthenticationEntryPoint(objectMapper); }

        @Bean
        public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) { return new RestAccessDeniedHandler(objectMapper); }

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, LoginUserLoader loginUserLoader, SecurityProperties securityProperties) {
            return new JwtAuthenticationFilter(jwtTokenProvider, loginUserLoader, securityProperties,
            org.mockito.Mockito.mock(com.sw.ck.security.cache.LoginUserCacheService.class));
        }

        @Bean
        public UserDetailsService noopUserDetailsService() {
            return username -> { throw new UsernameNotFoundException("本系统认证不经过 UserDetailsService：" + username); };
        }

        @Bean
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
                @Override public Long getUserId() { LoginUser u = LoginUserHolder.get(); return u != null ? u.getUserId() : null; }
                @Override public Long getTenantId() { LoginUser u = LoginUserHolder.get(); return u != null ? u.getTenantId() : null; }
                @Override public Long getDeptId() { LoginUser u = LoginUserHolder.get(); return u != null ? u.getDeptId() : null; }
                @Override public DataScopeType getDataScopeType() { return DataScopeType.ALL; }
                @Override public Set<Long> getCustomDeptIds() { return Set.of(); }
                @Override public boolean isSuperAdmin() { LoginUser u = LoginUserHolder.get(); return u != null && u.isSuperAdmin(); }
            };
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) { return new CommonMetaObjectHandler(loginContextProvider); }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter, RestAuthenticationEntryPoint authenticationEntryPoint, RestAccessDeniedHandler accessDeniedHandler) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        public MockMvc mockMvc(WebApplicationContext context, @Qualifier("springSecurityFilterChain") Filter springSecurityFilterChain) {
            return MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        }

        @RestControllerAdvice
        static class TestBusinessExceptionAdvice {
            @ExceptionHandler(BaseException.class)
            public R<Void> handleBaseException(BaseException ex) { return R.fail(ex.getCode(), ex.getMessage()); }
        }

        @Bean
        public TestBusinessExceptionAdvice testBusinessExceptionAdvice() { return new TestBusinessExceptionAdvice(); }
    }
}
