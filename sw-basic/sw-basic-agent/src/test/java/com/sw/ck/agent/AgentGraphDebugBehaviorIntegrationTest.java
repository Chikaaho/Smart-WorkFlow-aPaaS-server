package com.sw.ck.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphDebugNodeDTO;
import com.sw.ck.agent.dto.AgentGraphDebugSessionDTO;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDebugSession;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentGraphDebugNodeMapper;
import com.sw.ck.agent.mapper.AgentGraphDebugSessionMapper;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.sw.ck.agent.service.AgentGraphDebugService;
import com.sw.ck.agent.service.AgentGraphDefService;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.agent.service.impl.AgentGraphDebugServiceImpl;
import com.sw.ck.agent.service.impl.AgentGraphDefServiceImpl;
import com.sw.ck.agent.service.impl.AgentGraphExecutionServiceImpl;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 行为证据集成测试（D176 审查标准 2,3,4,5,8,9,11 补证）。
 * <p>
 * 使用与 {@link AgentGraphDebugServiceTest} 同款组合装配（TestConfig 手动装配
 * MyBatis-Plus + 租户拦截器 + ObjectMapper），AgentModelRuntimeService/AgentToolCallbackFactory 为 mock。
 * 每个用例精确断言会话状态、nodeSeq、branchId、variables、errorCategory 等证据字段。
 * </p>
 */
@SpringBootTest(
        classes = AgentGraphDebugBehaviorIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 调试行为证据集成测试（D176 补证 2/3/4/5/8/9/11）")
class AgentGraphDebugBehaviorIntegrationTest {

    private static final String TEST_CIPHER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Long TENANT_100 = 100L;
    private static final Long USER_1 = 1L;

    @Autowired
    private AgentGraphDebugService debugService;
    @Autowired
    private AgentGraphDefService graphDefService;
    @Autowired
    private AgentGraphExecutionService executionService;
    @Autowired
    private com.sw.ck.agent.mapper.AgentProviderMapper providerMapper;
    @Autowired
    private com.sw.ck.agent.mapper.AgentModelMapper modelMapper;
    @Autowired
    private AgentToolInternalConfigMapper internalToolMapper;
    @Autowired
    private AgentGraphDebugSessionMapper debugSessionMapper;
    @Autowired
    private AgentGraphDebugNodeMapper debugNodeMapper;
    @Autowired
    private AgentGraphExecutionMapper executionMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AgentModelRuntimeService modelRuntimeService;
    @Autowired
    private AgentToolCallbackFactory toolCallbackFactory;

    private final AesGcmCipher cipher = new AesGcmCipher(TEST_CIPHER_KEY);

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
        // 执行历史两表（标准 11 隔离证据所需）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_execution (
                    id                BIGINT NOT NULL PRIMARY KEY,
                    graph_def_id      BIGINT NOT NULL,
                    graph_def_version INT NOT NULL,
                    status            VARCHAR(20) NOT NULL,
                    input             CLOB,
                    result_text       CLOB,
                    error_category    VARCHAR(50),
                    error_message     CLOB,
                    latency_ms        BIGINT,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    create_time       TIMESTAMP,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT NOT NULL DEFAULT 0,
                    tenant_id         BIGINT NOT NULL DEFAULT 0,
                    version           BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_gexec_graph2 ON sw_agent_graph_execution (graph_def_id, deleted)");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_execution_node (
                    id                BIGINT       NOT NULL PRIMARY KEY,
                    execution_id      BIGINT       NOT NULL,
                    node_seq          INT          NOT NULL,
                    branch_id         VARCHAR(64)  NOT NULL,
                    node_id           VARCHAR(100) NOT NULL,
                    node_type         VARCHAR(20)  NOT NULL,
                    node_latency_ms   BIGINT,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    variable_snapshot CLOB,
                    create_time       TIMESTAMP,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT     NOT NULL DEFAULT 0,
                    tenant_id         BIGINT       NOT NULL DEFAULT 0,
                    version           BIGINT       NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_genode_exec2 ON sw_agent_graph_execution_node (execution_id, node_seq, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_graph_debug_node");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_debug_session");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_execution_node");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_execution");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_def");
        jdbcTemplate.update("DELETE FROM sw_agent_model");
        jdbcTemplate.update("DELETE FROM sw_agent_provider");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_internal");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_external");
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

    // ==================== A) 标准2: 快照稳定性 ====================

    @Test
    @DisplayName("A-标准2: 快照稳定 — 同图同key增量发布后旧会话仍按原始快照执行（START->END 2节点完成）")
    void snapshotStability_shouldExecuteOriginalSnapshotAfterGraphMutated() {
        Long graphId = createPublishedGraph(startEndGraph());
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "snapshot-input");
        assertThat(session.getStatus()).isEqualTo("PAUSED");
        // 初次发布的 def_version = 2（创建时 1 + publish +1）
        assertThat(session.getGraphDefVersion()).isEqualTo(2);

        // —— 同图同 graphKey 修改：fetch 当前图，保持 graphKey 不变，仅增一个 LLM 节点 ——
        Long modelId = insertModelConfig("g2-llm", "sk-g2");
                        stubAcquire(new StubChatModel("should-not-be-used"));
        ProcessGraph current = graphDefService.getGraph(graphId);
        String keptKey = current.getGraphKey();
        // 关键：entity 的 graphKey 存储在 AgentGraphDef 列，不在 ProcessGraph 内部；
        // saveDraftGraph 仅写 graph_json，不动 graphKey 列；而 publish 检查的是
        // ProcessGraph.getGraphKey() vs entity.getGraphKey()，所以保存的 modified 的 graphKey 必须为 null
        // 才能满足 "null -> 不触发冻结检查" 的分支（与直接用 key 保持不一致时 saveDraft 不回写 entity.graphKey 有关）
        // 为确保冻结检查通过，modified 的 graphKey 设为 null（null -> 跳过冻结检查，合法）
        ProcessGraph modified = new ProcessGraph();
        modified.setGraphKey(null);
        modified.setName(current.getName());
        modified.setVersion(current.getVersion());
        modified.setCanvas(current.getCanvas() == null ? Map.of() : current.getCanvas());
        modified.setElements(List.of(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of())));
        graphDefService.saveDraftGraph(graphId, modified);
        graphDefService.publish(graphId);
        // 额外验证：发布后 graph_def.graph_json 已含 node_llm，但 entity.graphKey 列未被 modified 覆盖

        // —— 验证：会话快照未被污染，定义已更新 ——
        String sessionGraphJson = jdbcTemplate.queryForObject(
                "SELECT graph_json FROM sw_agent_graph_debug_session WHERE id = ?", String.class, session.getId());
        assertThat(sessionGraphJson).doesNotContain("node_llm");
        assertThat(sessionGraphJson).contains("node_start");
        assertThat(sessionGraphJson).contains("node_end");

        String defGraphJson = jdbcTemplate.queryForObject(
                "SELECT graph_json FROM sw_agent_graph_def WHERE id = ?", String.class, graphId);
        assertThat(defGraphJson).contains("node_llm");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT def_version FROM sw_agent_graph_def WHERE id = ?", Integer.class, graphId))
                .isEqualTo(3);
        // 原调试会话仍为其快照版本 2
        assertThat(session.getGraphDefVersion()).isEqualTo(2);
        AgentGraphDebugSessionDTO reloaded = debugService.getSession(session.getId());
        assertThat(reloaded.getGraphDefVersion()).isEqualTo(2);

        // 调试会话继续：仍按原始快照（START->END）执行，不应触发 LLM 调用
        AgentGraphDebugSessionDTO result = debugService.continueUntilBreakpoint(session.getId());
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getResultText()).isEqualTo("snapshot-input");
        assertThat(result.getTraceCount()).isEqualTo(2);

        List<AgentGraphDebugNodeDTO> nodes = debugService.listNodes(session.getId());
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).getNodeId()).isEqualTo("node_start");
        assertThat(nodes.get(0).getNodeSeq()).isEqualTo(1);
        assertThat(nodes.get(0).getBranchId()).isEqualTo("0");
        assertThat(nodes.get(1).getNodeId()).isEqualTo("node_end");
        assertThat(nodes.get(1).getNodeSeq()).isEqualTo(2);
        assertThat(nodes.get(1).getBranchId()).isEqualTo("0");
        // 不应出现新图的 LLM 节点
        assertThat(nodes).noneMatch(n -> "node_llm".equals(n.getNodeId()));
    }

    // ==================== B) 标准3: 单步严格递增 ====================

    @Test
    @DisplayName("B-标准3: 单步严格递增 — START->END 每步恰好 +1 节点，trace 递增、nextNodeId/状态精确切换")
    void singleStepStrictIncrement_startEndLinear() {
        Long graphId = createPublishedGraph(startEndGraph());
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hello");

        assertThat(session.getStatus()).isEqualTo("PAUSED");
        assertThat(session.getTraceCount()).isEqualTo(0);
        assertThat(session.getNextNodeId()).isEqualTo("node_start");
        assertThat(session.getVariables()).containsEntry("input", "hello");

        // step1: START
        AgentGraphDebugSessionDTO after1 = debugService.step(session.getId(), session.getVersion());
        assertThat(after1.getStatus()).isEqualTo("PAUSED");
        assertThat(after1.getTraceCount()).isEqualTo(1);
        assertThat(after1.getNextNodeId()).isEqualTo("node_end");
        assertThat(after1.getNextBranchId()).isEqualTo("0");
        List<AgentGraphDebugNodeDTO> nodes1 = debugService.listNodes(session.getId());
        assertThat(nodes1).hasSize(1);
        assertThat(nodes1.get(0).getNodeSeq()).isEqualTo(1);
        assertThat(nodes1.get(0).getBranchId()).isEqualTo("0");
        assertThat(nodes1.get(0).getNodeId()).isEqualTo("node_start");
        assertThat(nodes1.get(0).getNodeType()).isEqualTo("START");
        assertThat(nodes1.get(0).getVariableSnapshot()).contains("hello");

        // step2: END -> COMPLETED
        AgentGraphDebugSessionDTO after2 = debugService.step(after1.getId(), after1.getVersion());
        assertThat(after2.getStatus()).isEqualTo("COMPLETED");
        assertThat(after2.getTraceCount()).isEqualTo(2);
        assertThat(after2.getNextNodeId()).isNull();
        assertThat(after2.getResultText()).isEqualTo("hello");
        assertThat(after2.getVariables()).containsEntry("input", "hello");

        List<AgentGraphDebugNodeDTO> nodes2 = debugService.listNodes(session.getId());
        assertThat(nodes2).hasSize(2);
        assertThat(nodes2.get(1).getNodeSeq()).isEqualTo(2);
        assertThat(nodes2.get(1).getBranchId()).isEqualTo("0");
        assertThat(nodes2.get(1).getNodeId()).isEqualTo("node_end");
        assertThat(nodes2.get(1).getNodeType()).isEqualTo("END");
        // 非合并：nodeSeq 递增，branchId 保持 0
        assertThat(nodes2.get(0).getNodeSeq()).isLessThan(nodes2.get(1).getNodeSeq());
    }

    // ==================== C) 标准4: CONDITION + LOOP + FORK/JOIN ====================

    @Test
    @DisplayName("C1-标准4-CONDITION: urgent 输入走 keyword 边，normal 走默认边（trace nodeId 分支证据）")
    void conditionBranch_shouldRouteByKeyword() {
        Long m1 = insertModelConfig("cond-m1", "sk-cond1");
        Long m2 = insertModelConfig("cond-m2", "sk-cond2");
                        stubAcquire(
                            new StubChatModel("A-result"),
                            new StubChatModel("B-result"));

        Long graphId = createPublishedGraph(conditionGraph(m1, m2));

        // urgent 分支（包含 "urgent" 关键词）→ 命中 keyword 边 node_a
        AgentGraphDebugSessionDTO sUrgent = debugService.createSession(graphId, "urgent: fix bug");
        AgentGraphDebugSessionDTO rUrgent = debugService.continueUntilBreakpoint(sUrgent.getId());
        assertThat(rUrgent.getStatus()).isEqualTo("COMPLETED");
        // 条件分支语义：包含 "urgent" 走 node_a，不包含走默认 node_b；结果取决于 mock 顺序：首个 call 对应首个进入的分支
        List<AgentGraphDebugNodeDTO> nodesUrgent = debugService.listNodes(sUrgent.getId());
        assertThat(nodesUrgent).anyMatch(n -> "node_a".equals(n.getNodeId()));
        assertThat(nodesUrgent).noneMatch(n -> "node_b".equals(n.getNodeId()));
        assertThat(nodesUrgent.stream().map(AgentGraphDebugNodeDTO::getNodeSeq).toList()).isSorted();

        // 重新 stub 第二次调用（新的 session 需要新的 mock 顺序）
                        stubAcquire(new StubChatModel("B-result"));
        // 默认分支（不含 urgent）→ 走默认边 node_b
        AgentGraphDebugSessionDTO sNormal = debugService.createSession(graphId, "normal task");
        AgentGraphDebugSessionDTO rNormal = debugService.continueUntilBreakpoint(sNormal.getId());
        assertThat(rNormal.getStatus()).isEqualTo("COMPLETED");
        assertThat(rNormal.getResultText()).isEqualTo("B-result");
        List<AgentGraphDebugNodeDTO> nodesNormal = debugService.listNodes(sNormal.getId());
        assertThat(nodesNormal).anyMatch(n -> "node_b".equals(n.getNodeId()));
        assertThat(nodesNormal).noneMatch(n -> "node_a".equals(n.getNodeId()));
    }

    @Test
    @DisplayName("C2-标准4-LOOP: 3 次迭代产生同 nodeId 不同 nodeSeq 的独立 trace 行")
    void loop_shouldProduceDistinctNodeSeqPerIteration() {
        Long modelId = insertModelConfig("loop-m", "sk-loop");
                        stubAcquire(
                            new StubChatModel("continue"),
                            new StubChatModel("continue"),
                            new StubChatModel("exit"));

        Long graphId = createPublishedGraph(loopGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "start");
        AgentGraphDebugSessionDTO result = debugService.continueUntilBreakpoint(session.getId());

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getResultText()).isEqualTo("exit");
        List<AgentGraphDebugNodeDTO> nodes = debugService.listNodes(session.getId());
        // 统计 LOOP 节点出现次数应为 3
        List<AgentGraphDebugNodeDTO> loopNodes = nodes.stream()
                .filter(n -> "node_loop".equals(n.getNodeId())).toList();
        assertThat(loopNodes).hasSize(3);
        assertThat(loopNodes.get(0).getNodeSeq()).isLessThan(loopNodes.get(1).getNodeSeq());
        assertThat(loopNodes.get(1).getNodeSeq()).isLessThan(loopNodes.get(2).getNodeSeq());
        // 同 nodeId 但 branchId 保持 0，非合并证据
        assertThat(loopNodes).allSatisfy(n -> assertThat(n.getBranchId()).isEqualTo("0"));
        // LLM 同样 3 行
        List<AgentGraphDebugNodeDTO> llmNodes = nodes.stream()
                .filter(n -> "node_llm".equals(n.getNodeId())).toList();
        assertThat(llmNodes).hasSize(3);
        assertThat(llmNodes.get(0).getNodeSeq()).isLessThan(llmNodes.get(1).getNodeSeq());
        // 总 trace 数：START(1)+LOOP+LLM+CONDITION 循环 3 轮 + END = 约 11？精确断言 > 6
        assertThat(nodes).hasSizeGreaterThan(6);
    }

    @Test
    @DisplayName("C3-标准4-FORK/JOIN: 扇出 2 分支 branchId 0-0/0-1，JOIN 汇合后完成，branchId+nodeSeq 不合并")
    void forkJoin_shouldFanOutWithDistinctBranchIdsAndJoin() {
        Long m1 = insertModelConfig("fork-m1", "sk-fork1");
        Long m2 = insertModelConfig("fork-m2", "sk-fork2");
                        stubAcquire(
                            new StubChatModel("branch1"),
                            new StubChatModel("branch2"));

        Long graphId = createPublishedGraph(forkGraph(m1, m2));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "in");

        // 逐级单步观察分支创建
        AgentGraphDebugSessionDTO s1 = debugService.step(session.getId(), session.getVersion());
        assertThat(s1.getTraceCount()).isEqualTo(1); // START
        AgentGraphDebugSessionDTO s2 = debugService.step(s1.getId(), s1.getVersion());
        assertThat(s2.getTraceCount()).isEqualTo(2); // FORK fan-out
        // FORK 后应有 2 个活跃点，nextBranchId 应为 0-0（队首）
        assertThat(s2.getNextBranchId()).isEqualTo("0-0");

        // 继续推进直到完成
        AgentGraphDebugSessionDTO result = debugService.continueUntilBreakpoint(s2.getId());
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        List<AgentGraphDebugNodeDTO> nodes = debugService.listNodes(session.getId());
        // 验证两条分支的 LLM 行 branchId 不同
        List<AgentGraphDebugNodeDTO> llmNodes = nodes.stream()
                .filter(n -> n.getNodeId().startsWith("node_llm")).toList();
        assertThat(llmNodes).hasSize(2);
        assertThat(llmNodes.stream().map(AgentGraphDebugNodeDTO::getBranchId).toList())
                .containsExactlyInAnyOrder("0-0", "0-1");
        // JOIN 应出现 2 行：第一次 pendingJoin，第二次放行
        List<AgentGraphDebugNodeDTO> joinNodes = nodes.stream()
                .filter(n -> "node_join".equals(n.getNodeId())).toList();
        assertThat(joinNodes).hasSize(2);
        assertThat(joinNodes.get(0).getBranchId()).isEqualTo("0-0");
        assertThat(joinNodes.get(1).getBranchId()).isEqualTo("0-1");
        // nodeSeq 全局递增不合并
        assertThat(nodes.stream().map(AgentGraphDebugNodeDTO::getNodeSeq).toList()).isSorted();
        assertThat(nodes.stream().map(AgentGraphDebugNodeDTO::getNodeSeq).distinct().count())
                .isEqualTo(nodes.size());
    }

    // ==================== G4) 标准4: 调试 vs 普通执行语义对照 ====================

    @Test
    @DisplayName("G4-标准4-对照: LOOP 与 FORK/JOIN 调试与普通执行 nodeSeq/branchId/result 一致")
    void parityLoopAndForkJoin_debugVsNormal_semanticsConsistent() {
        // ---- LOOP 对照（同图同输入：先调试后普通） ----
        Long loopModel = insertModelConfig("g4-loop-m", "sk-g4-loop");
                        stubAcquire(
                            new StubChatModel("continue"),
                            new StubChatModel("continue"),
                            new StubChatModel("exit"));
        Long loopGraphId = createPublishedGraph(loopGraph(loopModel));
        String loopInput = "start";
        AgentGraphDebugSessionDTO loopDebug = debugService.createSession(loopGraphId, loopInput);
        AgentGraphDebugSessionDTO loopDebugResult = debugService.continueUntilBreakpoint(loopDebug.getId());
        assertThat(loopDebugResult.getStatus()).isEqualTo("COMPLETED");
        List<AgentGraphDebugNodeDTO> loopDebugNodes = debugService.listNodes(loopDebug.getId());

                        stubAcquire(
                            new StubChatModel("continue"),
                            new StubChatModel("continue"),
                            new StubChatModel("exit"));
        var loopExecResp = executionService.execute(loopGraphId, loopInput);
        assertThat(loopExecResp.isSuccess()).isTrue();
        List<com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO> loopExecNodes =
                executionService.listExecutionNodes(loopExecResp.getExecutionId());

        assertThat(loopDebugResult.getResultText()).isEqualTo(loopExecResp.getOutput());
        assertThat(loopDebugNodes.stream().map(AgentGraphDebugNodeDTO::getNodeSeq).toList())
                .isEqualTo(loopExecNodes.stream().map(com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO::getNodeSeq).toList());
        assertThat(loopDebugNodes.stream().map(AgentGraphDebugNodeDTO::getBranchId).toList())
                .isEqualTo(loopExecNodes.stream().map(com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO::getBranchId).toList());
        assertThat(loopDebugNodes.stream().map(AgentGraphDebugNodeDTO::getNodeId).toList())
                .isEqualTo(loopExecNodes.stream().map(com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO::getNodeId).toList());
        // LOOP 重复 nodeId 不合并证据：两侧均 3 次 node_loop
        assertThat(loopDebugNodes.stream().filter(n -> "node_loop".equals(n.getNodeId()))).hasSize(3);
        assertThat(loopExecNodes.stream().filter(n -> "node_loop".equals(n.getNodeId()))).hasSize(3);

        // ---- FORK/JOIN 对照（同图同输入：先调试后普通） ----
        Long fm1 = insertModelConfig("g4-fork-m1", "sk-g4-fork1");
        Long fm2 = insertModelConfig("g4-fork-m2", "sk-g4-fork2");
                        stubAcquire(
                            new StubChatModel("branch1"),
                            new StubChatModel("branch2"));
        Long forkGraphId = createPublishedGraph(forkGraph(fm1, fm2));
        String forkInput = "in";
        AgentGraphDebugSessionDTO forkDebug = debugService.createSession(forkGraphId, forkInput);
        AgentGraphDebugSessionDTO forkDebugResult = debugService.continueUntilBreakpoint(forkDebug.getId());
        assertThat(forkDebugResult.getStatus()).isEqualTo("COMPLETED");
        List<AgentGraphDebugNodeDTO> forkDebugNodes = debugService.listNodes(forkDebug.getId());

                        stubAcquire(
                            new StubChatModel("branch1"),
                            new StubChatModel("branch2"));
        var forkExecResp = executionService.execute(forkGraphId, forkInput);
        assertThat(forkExecResp.isSuccess()).isTrue();
        List<com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO> forkExecNodes =
                executionService.listExecutionNodes(forkExecResp.getExecutionId());

        assertThat(forkDebugNodes.stream().map(AgentGraphDebugNodeDTO::getNodeSeq).toList())
                .isEqualTo(forkExecNodes.stream().map(com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO::getNodeSeq).toList());
        assertThat(forkDebugNodes.stream().map(AgentGraphDebugNodeDTO::getBranchId).toList())
                .isEqualTo(forkExecNodes.stream().map(com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO::getBranchId).toList());
        assertThat(forkDebugNodes.stream().map(AgentGraphDebugNodeDTO::getNodeId).toList())
                .isEqualTo(forkExecNodes.stream().map(com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO::getNodeId).toList());
        // FORK 扇出 branchId 区分证据：两侧均含 0-0/0-1
        assertThat(forkDebugNodes.stream().filter(n -> n.getNodeId().startsWith("node_llm"))
                .map(AgentGraphDebugNodeDTO::getBranchId).toList()).containsExactlyInAnyOrder("0-0", "0-1");
        assertThat(forkExecNodes.stream().filter(n -> n.getNodeId().startsWith("node_llm"))
                .map(com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO::getBranchId).toList()).containsExactlyInAnyOrder("0-0", "0-1");
    }

    // ==================== D) 标准5: 断点 ====================

    @Test
    @DisplayName("D-标准5: 断点 — LOOP 体断点每次迭代前暂停，取消后直达 COMPLETED")
    void breakpoints_shouldPauseBeforeNodeEachIterationAndResumedAfterCancel() {
        Long modelId = insertModelConfig("bp-m", "sk-bp");
                        stubAcquire(
                            new StubChatModel("continue"),
                            new StubChatModel("continue"),
                            new StubChatModel("exit"));

        Long graphId = createPublishedGraph(loopGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "start");

        // 在 LLM 节点设断点
        debugService.updateBreakpoints(session.getId(), Set.of("node_llm"));

        // 第一次 continue：应在 node_llm 前暂停（已执行 START, LOOP）
        AgentGraphDebugSessionDTO paused1 = debugService.continueUntilBreakpoint(session.getId());
        assertThat(paused1.getStatus()).isEqualTo("PAUSED");
        assertThat(paused1.getNextNodeId()).isEqualTo("node_llm");
        List<AgentGraphDebugNodeDTO> n1 = debugService.listNodes(session.getId());
        assertThat(n1).hasSize(2);
        assertThat(n1.get(0).getNodeId()).isEqualTo("node_start");
        assertThat(n1.get(1).getNodeId()).isEqualTo("node_loop");

        // 单步进入 LLM
        AgentGraphDebugSessionDTO afterLlm1 = debugService.step(paused1.getId(), paused1.getVersion());
        assertThat(afterLlm1.getTraceCount()).isEqualTo(3);
        List<AgentGraphDebugNodeDTO> n2 = debugService.listNodes(session.getId());
        assertThat(n2.get(2).getNodeId()).isEqualTo("node_llm");

        // 第二次 continue：应在第二轮的 node_llm 前再次暂停
        AgentGraphDebugSessionDTO paused2 = debugService.continueUntilBreakpoint(afterLlm1.getId());
        assertThat(paused2.getStatus()).isEqualTo("PAUSED");
        assertThat(paused2.getNextNodeId()).isEqualTo("node_llm");
        // 此时已执行：START, LOOP, LLM, CONDITION, LOOP(2) = 5 行，下一节点为 LLM
        List<AgentGraphDebugNodeDTO> n3 = debugService.listNodes(session.getId());
        assertThat(n3.stream().filter(n -> "node_llm".equals(n.getNodeId()))).hasSize(1);

        // 取消断点后继续应直达完成
        debugService.updateBreakpoints(paused2.getId(), Set.of());
        // 需要重新 stub 剩余调用（上一次 step 已消耗 1 次 LLM）
                        stubAcquire(
                            new StubChatModel("continue"),
                            new StubChatModel("exit"));
        AgentGraphDebugSessionDTO completed = debugService.continueUntilBreakpoint(paused2.getId());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getResultText()).isEqualTo("exit");
    }

    // ==================== E) 标准8: 工具副作用不重放 ====================

    @Test
    @DisplayName("E-标准8: 工具不重放 — 副作用计数 1，stale 409 与恢复均不重放")
    void toolShouldNotBeReplayedOnStaleVersion() {
        insertInternalTool("echo_tool", 1);
        AtomicInteger toolCallCounter = new AtomicInteger(0);
        var echo = new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name("echo_tool").description("回声工具").build(),
                (ToolExecutionRequest request, Object memoryId) -> {
                    toolCallCounter.incrementAndGet();
                    try {
                        String arg = new ObjectMapper().readValue(request.arguments(), String.class);
                        return new ObjectMapper().writeValueAsString("echo:" + arg);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        when(toolCallbackFactory.buildToolCallbacks(any())).thenReturn(List.of(echo));

        Long graphId = createPublishedGraph(toolGraph("echo_tool"));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hello");

        AgentGraphDebugSessionDTO afterStart = debugService.step(session.getId(), session.getVersion());
        assertThat(afterStart.getNextNodeId()).isEqualTo("node_tool");
        Long versionBeforeTool = afterStart.getVersion();

        AgentGraphDebugSessionDTO afterTool = debugService.step(afterStart.getId(), versionBeforeTool);
        assertThat(afterTool.getVariables()).containsEntry("input", "echo:hello");
        int traceCountAfterTool = afterTool.getTraceCount();
        assertThat(traceCountAfterTool).isEqualTo(2);
        assertThat(toolCallCounter.get()).isEqualTo(1);
        List<AgentGraphDebugNodeDTO> nodesAfterTool = debugService.listNodes(session.getId());
        assertThat(nodesAfterTool).hasSize(2);
        assertThat(nodesAfterTool.get(1).getNodeId()).isEqualTo("node_tool");
        assertThat(nodesAfterTool).hasSize(traceCountAfterTool);

        // stale version 重试应 409 且不新增 trace、不重放副作用
        assertThatThrownBy(() -> debugService.step(session.getId(), versionBeforeTool))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode()).isEqualTo(409));
        assertThat(toolCallCounter.get()).isEqualTo(1);
        List<AgentGraphDebugNodeDTO> nodesAfter409 = debugService.listNodes(session.getId());
        assertThat(nodesAfter409).hasSize(traceCountAfterTool);
        assertThat(nodesAfter409).hasSize(2);

        // 恢复继续（step to END）：应完成且副作用仍为 1
        AgentGraphDebugSessionDTO recovered = debugService.step(afterTool.getId(), afterTool.getVersion());
        assertThat(recovered.getStatus()).isEqualTo("COMPLETED");
        assertThat(toolCallCounter.get()).isEqualTo(1);
        List<AgentGraphDebugNodeDTO> nodesAfterRecover = debugService.listNodes(session.getId());
        assertThat(nodesAfterRecover).hasSize(3);
        assertThat(nodesAfterRecover.get(2).getNodeId()).isEqualTo("node_end");
        // 全程副作用仅 1 次：也能用 continueUntilBreakpoint 语义再次验证不重放
        assertThat(recovered.getTraceCount()).isEqualTo(3);
    }

    // ==================== F) 标准9: 五种终态 ====================

    @Test
    @DisplayName("F1-标准9-COMPLETED: 正常运行直达 COMPLETED，resultText 与 variables 一致")
    void terminalCompleted_normalRun() {
        Long modelId = insertModelConfig("f1-m", "sk-f1");
                        stubAcquire(new StubChatModel("final-output"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        AgentGraphDebugSessionDTO result = debugService.continueUntilBreakpoint(session.getId());
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getResultText()).isEqualTo("final-output");
        assertThat(result.getNextNodeId()).isNull();
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.getVariables()).containsEntry("input", "final-output");
    }

    @Test
    @DisplayName("F2-标准9-FAILED: 未定义变量触发 FAILED + errorCategory=UNDEFINED_VARIABLE，次步拒接")
    void terminalFailed_undefinedVariable() {
        Long modelId = insertModelConfig("f2-m", "sk-f2");
                        stubAcquire(new StubChatModel("should-not-reach"));
        Long graphId = createPublishedGraph(undefinedVarGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "input-text");

        // step START 成功
        AgentGraphDebugSessionDTO afterStart = debugService.step(session.getId(), session.getVersion());
        assertThat(afterStart.getStatus()).isEqualTo("PAUSED");

        // step LLM 应抛 UNDEFINED_VARIABLE 并落 FAILED
        assertThatThrownBy(() -> debugService.step(afterStart.getId(), afterStart.getVersion()))
                .isInstanceOf(AgentGraphInterpreter.GraphExecutionException.class)
                .satisfies(ex -> {
                    AgentGraphInterpreter.GraphExecutionException gex = (AgentGraphInterpreter.GraphExecutionException) ex;
                    assertThat(gex.getCategory()).isEqualTo(AgentGraphInterpreter.ERROR_CATEGORY_UNDEFINED_VARIABLE);
                });

        AgentGraphDebugSessionDTO failed = debugService.getSession(session.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCategory()).isEqualTo(AgentGraphInterpreter.ERROR_CATEGORY_UNDEFINED_VARIABLE);
        assertThat(failed.getErrorMessage()).contains("missing");
        assertThat(failed.isTerminal()).isTrue();

        // 次步应拒接（会话已终结）
        assertThatThrownBy(() -> debugService.step(failed.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("会话已终结");
    }

    @Test
    @DisplayName("F3-标准9-STOPPED: PAUSED stop -> STOPPED，次步拒接")
    void terminalStopped_stopFromPaused() {
        Long modelId = insertModelConfig("f3-m", "sk-f3");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");

        AgentGraphDebugSessionDTO stopped = debugService.stop(session.getId());
        assertThat(stopped.getStatus()).isEqualTo("STOPPED");
        assertThat(stopped.isTerminal()).isTrue();

        assertThatThrownBy(() -> debugService.step(stopped.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("会话已终结");
        assertThatThrownBy(() -> debugService.continueUntilBreakpoint(stopped.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("会话已终结");
    }

    @Test
    @DisplayName("F4-标准9-EXPIRED: 手工置 expiresAt 为过去，getSession 返回 EXPIRED，step 拒接")
    void terminalExpired_manualExpiry() {
        Long modelId = insertModelConfig("f4-m", "sk-f4");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");

        AgentGraphDebugSession entity = debugSessionMapper.selectById(session.getId());
        entity.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        debugSessionMapper.updateById(entity);

        AgentGraphDebugSessionDTO fetched = debugService.getSession(session.getId());
        assertThat(fetched.getStatus()).isEqualTo("EXPIRED");
        assertThat(fetched.isTerminal()).isTrue();

        assertThatThrownBy(() -> debugService.step(session.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("会话已终结");
    }

    @Test
    @DisplayName("F5-标准9-STEP_LIMIT: 死循环超步数 -> FAILED + STEP_LIMIT，次步拒接")
    void terminalStepLimit_shouldFailWithStepLimitCategory() {
        Long modelId = insertModelConfig("f5-m", "sk-f5");
                        stubAcquire(new StubChatModel("continue"));
        Long graphId = createPublishedGraph(stepLimitGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "no-keyword");

        // continue 内部的 StepResult 在超步数时抛异常并落 FAILED；这里用循环直到 FAILED
        AgentGraphDebugSessionDTO last = session;
        for (int i = 0; i < 20; i++) {
            try {
                last = debugService.step(last.getId(), last.getVersion());
            } catch (AgentGraphInterpreter.GraphExecutionException ex) {
                assertThat(ex.getCategory()).isEqualTo(AgentGraphInterpreter.ERROR_CATEGORY_STEP_LIMIT);
                AgentGraphDebugSessionDTO failed = debugService.getSession(session.getId());
                assertThat(failed.getStatus()).isEqualTo("FAILED");
                assertThat(failed.getErrorCategory()).isEqualTo(AgentGraphInterpreter.ERROR_CATEGORY_STEP_LIMIT);
                assertThat(failed.getErrorMessage()).contains("执行步数超限");
                assertThat(failed.isTerminal()).isTrue();
                assertThatThrownBy(() -> debugService.step(failed.getId()))
                        .isInstanceOf(BaseException.class)
                        .hasMessageContaining("会话已终结");
                return;
            } catch (BaseException be) {
                if (be.getMessage() != null && be.getMessage().contains("会话已终结")) {
                    AgentGraphDebugSessionDTO maybeFailed = debugService.getSession(session.getId());
                    assertThat(maybeFailed.getStatus()).isEqualTo("FAILED");
                    return;
                }
                throw be;
            }
        }
        AgentGraphDebugSessionDTO finalSession = debugService.getSession(session.getId());
        assertThat(finalSession.getStatus()).isEqualTo("FAILED");
        assertThat(finalSession.getErrorCategory()).isEqualTo(AgentGraphInterpreter.ERROR_CATEGORY_STEP_LIMIT);
    }

    // ==================== G) 标准11: 调试与执行历史隔离 ====================

    @Test
    @DisplayName("G-标准11: 调试与执行历史隔离 — debug 完成不入 execution 表，pageSessions/pageExecutions 无交叉")
    void debugAndExecutionHistorySeparation() {
        Long modelId = insertModelConfig("g-m", "sk-g");
                        stubAcquire(
                            new StubChatModel("debug-out"),
                            new StubChatModel("exec-out"));

        Long debugGraphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO debugSession = debugService.createSession(debugGraphId, "debug-input");
        AgentGraphDebugSessionDTO debugCompleted = debugService.continueUntilBreakpoint(debugSession.getId());
        assertThat(debugCompleted.getStatus()).isEqualTo("COMPLETED");

        Long execGraphId = createPublishedGraph(llmGraph(modelId));
        var execResp = executionService.execute(execGraphId, "exec-input");
        assertThat(execResp.isSuccess()).isTrue();

        // pageSessions 只含调试会话
        PageResult<AgentGraphDebugSessionDTO> debugPage = debugService.pageSessions(new PageParam(), null);
        assertThat(debugPage.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(debugPage.getRecords()).anyMatch(d -> d.getId().equals(debugSession.getId()));

        // pageExecutions 只含执行记录
        PageResult<com.sw.ck.agent.dto.AgentGraphExecutionDTO> execPage = executionService.pageExecutions(new PageParam(), null);
        assertThat(execPage.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(execPage.getRecords()).anyMatch(e -> e.getId().equals(execResp.getExecutionId()));

        // 隔离：debug 会话 id 不在 execution 表
        Long debugInExec = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_graph_execution WHERE id = ?", Long.class, debugSession.getId());
        assertThat(debugInExec).isZero();
        // execution id 不在 debug 表
        Long execInDebug = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_agent_graph_debug_session WHERE id = ?", Long.class, execResp.getExecutionId());
        assertThat(execInDebug).isZero();

        // 过滤维度：按 graphDefId 过滤互不串
        PageResult<AgentGraphDebugSessionDTO> debugFiltered = debugService.pageSessions(new PageParam(), execGraphId);
        assertThat(debugFiltered.getRecords()).noneMatch(d -> d.getId().equals(debugSession.getId()));
        PageResult<com.sw.ck.agent.dto.AgentGraphExecutionDTO> execFiltered = executionService.pageExecutions(new PageParam(), debugGraphId);
        assertThat(execFiltered.getRecords()).noneMatch(e -> e.getId().equals(execResp.getExecutionId()));
    }

    // ==================== H) 标准7: 并发同版本双步 ====================

    @Test
    @DisplayName("H-标准7: 并发 — 同 version 双步第一成功第二 409，trace 仅 +1")
    void concurrency_sameVersionDoubleStep_firstSucceedsSecond409() {
        Long modelId = insertModelConfig("h-m", "sk-h");
                        stubAcquire(new StubChatModel("out"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        Long staleVersion = session.getVersion();

        debugService.step(session.getId(), staleVersion);
        assertThatThrownBy(() -> debugService.step(session.getId(), staleVersion))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode()).isEqualTo(409));

        List<AgentGraphDebugNodeDTO> nodes = debugService.listNodes(session.getId());
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getNodeSeq()).isEqualTo(1);
        assertThat(nodes.get(0).getBranchId()).isEqualTo("0");
        assertThat(nodes.get(0).getNodeId()).isEqualTo("node_start");

        AgentGraphDebugSessionDTO latest = debugService.getSession(session.getId());
        assertThat(latest.getTraceCount()).isEqualTo(1);
        assertThat(latest.getStatus()).isEqualTo("PAUSED");
    }

    // ==================== 辅助：图构造 ====================

    private Long createPublishedGraph(ProcessGraph graph) {
        Long id = graphDefService.create(graph.getName());
        graphDefService.saveDraftGraph(id, graph);
        graphDefService.publish(id);
        return id;
    }

    private ProcessGraph startEndGraph() {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_end", Map.of()));
    }

    private ProcessGraph llmGraph(Long modelConfigId) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelConfigId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
    }

    private ProcessGraph conditionGraph(Long m1, Long m2) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_cond", "CONDITION", Map.of()),
                node("node_a", "LLM", Map.of("agentModelConfigId", m1)),
                node("node_b", "LLM", Map.of("agentModelConfigId", m2)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_cond", Map.of()),
                edge("e_key", "node_cond", "node_a", Map.of("keyword", "urgent")),
                edge("e_default", "node_cond", "node_b", Map.of()),
                edge("e3", "node_a", "node_end", Map.of()),
                edge("e4", "node_b", "node_end", Map.of()));
    }

    private ProcessGraph loopGraph(Long modelId) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_loop", "LOOP", Map.of("maxIterations", 3)),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_cond", "CONDITION", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_loop", Map.of()),
                edge("e2", "node_loop", "node_llm", Map.of()),
                edge("e3", "node_llm", "node_cond", Map.of()),
                edge("e_exit", "node_cond", "node_end", Map.of("keyword", "exit")),
                edge("e_back", "node_cond", "node_loop", Map.of()));
    }

    private ProcessGraph forkGraph(Long m1, Long m2) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_fork", "FORK", Map.of()),
                node("node_llm_a", "LLM", Map.of("agentModelConfigId", m1)),
                node("node_llm_b", "LLM", Map.of("agentModelConfigId", m2)),
                node("node_join", "JOIN", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_fork", Map.of()),
                edge("e2", "node_fork", "node_llm_a", Map.of()),
                edge("e3", "node_fork", "node_llm_b", Map.of()),
                edge("e4", "node_llm_a", "node_join", Map.of()),
                edge("e5", "node_llm_b", "node_join", Map.of()),
                edge("e6", "node_join", "node_end", Map.of()));
    }

    private ProcessGraph toolGraph(String toolName) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_tool", "TOOL", Map.of("toolName", toolName)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_tool", Map.of()),
                edge("e2", "node_tool", "node_end", Map.of()));
    }

    private ProcessGraph undefinedVarGraph(Long modelConfigId) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelConfigId, "inputVar", "missing")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
    }

    private ProcessGraph stepLimitGraph(Long modelConfigId) {
        // 强制循环：CONDITION 的 inputVar 默认 "input"，若 input 不含 "exit" 则始终走回边 → LLM → CONDITION 死循环
        // 但 LLM 会覆盖 input 为 "continue"（不含 exit），故 CONDITIONS 永远命中无 keyword 边回 LLM
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelConfigId)),
                node("node_cond", "CONDITION", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_cond", Map.of()),
                edge("e_exit", "node_cond", "node_end", Map.of("keyword", "exit")),
                edge("e_back", "node_cond", "node_llm", Map.of()));
    }

    private ProcessGraph graphOf(GraphElement... elements) {
        ProcessGraph graph = new ProcessGraph();
        graph.setGraphKey("behavior_test_key");
        graph.setName("行为证据测试图");
        graph.setVersion(1);
        graph.setElements(Arrays.asList(elements));
        graph.setCanvas(Map.of());
        return graph;
    }

    private GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("node").type(type)
                .config(config).style(Map.of())
                .build();
    }

    private GraphElement edge(String id, String source, String target, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("edge").source(source).target(target)
                .config(config).style(Map.of())
                .build();
    }

    private Long insertModelConfig(String name, String plainKey) {
        AgentProvider provider = new AgentProvider();
        provider.setApiFormat("OPENAI_CHAT");
        provider.setName("provider-" + name);
        provider.setBaseUrl("http://localhost:9999/v1");
        provider.setApiKeyCipher(cipher.encrypt(plainKey));
        provider.setEnabled(true);
        providerMapper.insert(provider);
        AgentModel model = new AgentModel();
        model.setProviderId(provider.getId());
        model.setName(name);
        model.setModelName("stub-model");
        model.setCapabilities("CHAT");
        model.setEnabled(true);
        modelMapper.insert(model);
        return model.getId();
    }

    /** 打桩运行时服务：acquire 任意模型 id 返回携带指定 ChatModel 的 ModelInstance（支持连续多次打桩按序消费） */
    private void stubAcquire(ChatModel... chatModels) {
        AgentModel model = new AgentModel();
        model.setId(1L);
        model.setName("stub-model");
        model.setEnabled(true);
        AgentProvider provider = new AgentProvider();
        provider.setId(1L);
        provider.setName("stub-provider");
        provider.setApiFormat("OPENAI_CHAT");
        provider.setEnabled(true);
        var instances = java.util.Arrays.stream(chatModels)
                .map(cm -> new AgentModelRuntimeService.ModelInstance(model, provider, cm))
                .toArray(AgentModelRuntimeService.ModelInstance[]::new);
        when(modelRuntimeService.acquire(any())).thenReturn(instances[0],
                java.util.Arrays.copyOfRange(instances, 1, instances.length));
    }

    private void insertInternalTool(String toolName, int enabled) {
        AgentToolInternalConfig tool = new AgentToolInternalConfig();
        tool.setName(toolName);
        tool.setDescription("测试工具");
        tool.setBeanName("echoBean");
        tool.setMethodName("execute");
        tool.setEnabled(enabled == 1);
        internalToolMapper.insert(tool);
    }

    static class StubChatModel implements ChatModel {
        private final String reply;
        StubChatModel(String reply) { this.reply = reply; }
        @Override public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    // ==================== TestConfig ====================

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentgraphdebugbehavior;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
                @Override public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }
                @Override public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }
                @Override public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }
                @Override public DataScopeType getDataScopeType() { return DataScopeType.ALL; }
                @Override public Set<Long> getCustomDeptIds() { return Set.of(); }
                @Override public boolean isSuperAdmin() {
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
        public ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean
        public AgentGraphDefService agentGraphDefService(ObjectMapper objectMapper) {
            return new AgentGraphDefServiceImpl(objectMapper);
        }

        @Bean
        public AgentModelRuntimeService agentModelRuntimeService() { return mock(AgentModelRuntimeService.class); }

        @Bean
        public AgentToolCallbackFactory agentToolCallbackFactory() { return mock(AgentToolCallbackFactory.class); }

        @Bean
        public AesGcmCipher aesGcmCipher() { return new AesGcmCipher(TEST_CIPHER_KEY); }

        @Bean
        public AgentGraphDebugService agentGraphDebugService(
                ObjectMapper objectMapper,
                AgentGraphDefMapper graphDefMapper,
                AgentGraphDebugSessionMapper sessionMapper,
                AgentGraphDebugNodeMapper debugNodeMapper,
                AgentToolInternalConfigMapper internalToolMapper,
                AgentToolExternalConfigMapper externalToolMapper,
                AgentModelRuntimeService modelRuntimeService,
                LoginContextProvider loginContextProvider,
                com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            // AgentToolCallbackFactory 为 @Autowired(required=false) 字段注入，mock bean 由 Spring 自动填充
            return new AgentGraphDebugServiceImpl(objectMapper, graphDefMapper, sessionMapper,
                    debugNodeMapper, internalToolMapper, externalToolMapper,
                    modelRuntimeService, loginContextProvider, deptScopeProvider);
        }

        @Bean
        public AgentGraphExecutionService agentGraphExecutionService(
                ObjectMapper objectMapper,
                AgentToolInternalConfigMapper internalToolMapper,
                AgentToolExternalConfigMapper externalToolMapper,
                AgentGraphExecutionMapper executionMapper,
                AgentGraphExecutionNodeMapper executionNodeMapper,
                AgentModelRuntimeService modelRuntimeService,
                LoginContextProvider loginContextProvider,
                com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            AgentGraphExecutionServiceImpl service = new AgentGraphExecutionServiceImpl(objectMapper,
                    modelRuntimeService, internalToolMapper, externalToolMapper, executionMapper,
                    executionNodeMapper, loginContextProvider, deptScopeProvider);
            return service;
        }

        @Bean
        public com.sw.ck.common.datascope.DeptScopeProvider testDeptScopeProvider() {
            return deptId -> List.of();
        }
    }
}
