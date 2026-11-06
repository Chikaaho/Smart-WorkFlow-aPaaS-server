package com.sw.ck.agent.orchestration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentToolCallbackFactory} 测试（F1 阶段 1 LangChain4j 化）。
 * <p>
 * 策略：mock 两个 Mapper（{@code selectList} 返回配置列表）+ mock {@link ApplicationContext}
 * （{@code getBean} 返回白名单 bean 或抛 {@link NoSuchBeanDefinitionException}）。
 * 外部工具调用用 JDK 内置 {@code com.sun.net.httpserver.HttpServer}（localhost 随机端口），
 * 超时行为用"服务端延迟响应 > 配置 readTimeout"行为验证。
 * </p>
 * <p>
 * LC4j 参数约定：工具 schema 固定单字符串属性 {@code input}；{@link ToolExecutor}
 * 收到的 {@code request.arguments()} 即 LLM 产出的该属性 JSON 值——字符串入参时
 * 为 JSON 字符串字面量（含首尾双引号），由工厂反序列化为原始字符串后反射调用。
 * </p>
 */
@DisplayName("工具回调工厂测试（LangChain4j ToolHandle）")
class AgentToolCallbackFactoryTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ==================== 用例 1：内部工具构造 + 调用链路 ====================

    @Test
    @DisplayName("用例1: 内部工具 ToolHandle 构造正确（name/description/单字符串 input schema），executor 链路到达白名单 bean 方法")
    void internalTool_shouldBuildHandleAndInvokeBean() {
        AgentToolInternalConfig config = internalConfig("sum_tool", "求和工具",
                "{\"type\":\"string\"}", "echoToolBean", "execute");

        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of(config));
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        EchoToolBean bean = new EchoToolBean();
        when(ctx.getBean("echoToolBean")).thenReturn(bean);

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(internalMapper, externalMapper, ctx);
        List<AgentToolCallbackFactory.ToolHandle> handles = factory.buildToolCallbacks(100L);

        assertThat(handles).hasSize(1);
        AgentToolCallbackFactory.ToolHandle handle = handles.get(0);
        assertThat(handle.specification().name()).isEqualTo("sum_tool");
        assertThat(handle.specification().description()).isEqualTo("求和工具");
        // DB inputSchema 不再拼进请求 schema：固定单字符串属性 input
        assertThat(handle.specification().parameters().properties()).containsKey("input");
        assertThat(handle.specification().parameters().required()).containsExactly("input");
        // executor 链路：arguments JSON 字符串字面量 → 反序列化原始字符串 → 反射调用 bean.execute(String)
        String result = handle.executor().execute(
                ToolExecutionRequest.builder().id("t1").name("sum_tool").arguments("\"{\\\"a\\\":1}\"").build(),
                "memory-1");
        assertThat(result).contains("echo:");
    }

    @Test
    @DisplayName("用例1b: 模型按声明返回 {input: \"JSON 字符串\"} 信封时，白名单方法收到内层入参原文")
    void internalTool_shouldUnwrapInputEnvelope() {
        AgentToolInternalConfig config = internalConfig("start_flow", "发起流程",
                "{\"type\":\"string\"}", "echoToolBean", "execute");

        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of(config));
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("echoToolBean")).thenReturn(new EchoToolBean());

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(internalMapper, externalMapper, ctx);
        AgentToolCallbackFactory.ToolHandle handle = factory.buildToolCallbacks(100L).get(0);
        String innerJson = "{\"templateKey\":\"bpm_demo\",\"formData\":{\"a\":1}}";
        String arguments = "{\"input\":\"" + innerJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        String result = handle.executor().execute(
                ToolExecutionRequest.builder().id("t2").name("start_flow").arguments(arguments).build(),
                "memory-1");
        assertThat(result).isEqualTo("echo:" + innerJson);
    }

    // ==================== 用例 2：外部工具构造 + 真实 HTTP 调用 ====================

    @Test
    @DisplayName("用例2: 外部工具 ToolHandle 构造正确，executor 发起 POST 到白名单 URL 并返回响应体")
    void externalTool_shouldBuildHandleAndCallMockServer() throws Exception {
        server = startServer((exchange, body) -> {
            String respBody = "pong:" + body;
            byte[] out = respBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        int port = server.getAddress().getPort();
        AgentToolExternalConfig config = externalConfig("weather_tool", "天气查询工具",
                "{\"type\":\"object\"}", "http://127.0.0.1:" + port + "/weather", "POST", 5);

        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of());
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of(config));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                internalMapper, externalMapper, mock(ApplicationContext.class));
        List<AgentToolCallbackFactory.ToolHandle> handles = factory.buildToolCallbacks(null);

        assertThat(handles).hasSize(1);
        AgentToolCallbackFactory.ToolHandle handle = handles.get(0);
        assertThat(handle.specification().name()).isEqualTo("weather_tool");
        assertThat(handle.specification().description()).isEqualTo("天气查询工具");

        String result = handle.executor().execute(
                ToolExecutionRequest.builder().id("t2").name("weather_tool")
                        .arguments("\"{\\\"city\\\":\\\"beijing\\\"}\"").build(),
                "memory-2");
        assertThat(result).contains("pong:").contains("city");
    }

    @Test
    @DisplayName("用例2b: GET 外部工具把入参编码进查询串（GET body 会被 JDK 客户端丢弃）")
    void externalTool_getShouldAppendArgsToQuery() throws Exception {
        String[] query = new String[1];
        server = startServer((exchange, body) -> {
            query[0] = exchange.getRequestURI().getRawQuery();
            byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        int port = server.getAddress().getPort();
        AgentToolExternalConfig config = externalConfig("search_tool", "搜索工具",
                "{\"type\":\"object\"}", "http://127.0.0.1:" + port + "/search?q=", "GET", 5);
        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of());
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of(config));
        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                internalMapper, externalMapper, mock(ApplicationContext.class));

        String result = factory.buildToolCallbacks(null).get(0).executor().execute(
                ToolExecutionRequest.builder().id("t2b").name("search_tool")
                        .arguments("\"{\\\"deviceName\\\":\\\"南大门\\\"}\"").build(),
                "memory-2b");

        assertThat(result).isEqualTo("ok");
        assertThat(query[0]).startsWith("q=%7B").contains("%22deviceName%22");
    }

    // ==================== 用例 3：外部工具超时从 DB 配置读 ====================

    @Test
    @DisplayName("用例3: timeoutSeconds 从配置读取（服务端延迟 3s > readTimeout 1s → 调用抛 SocketTimeoutException）")
    void externalTool_timeoutFromConfig_shouldApplyReadTimeout() throws Exception {
        server = startServer((exchange, body) -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] out = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        int port = server.getAddress().getPort();
        AgentToolExternalConfig config = externalConfig("slow_tool", "慢服务",
                null, "http://127.0.0.1:" + port + "/slow", "POST", 1);

        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of());
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of(config));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(
                internalMapper, externalMapper, mock(ApplicationContext.class));
        AgentToolCallbackFactory.ToolHandle handle = factory.buildToolCallbacks(null).get(0);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t3").name("slow_tool").arguments("\"ping\"").build();
        assertThatThrownBy(() -> handle.executor().execute(request, "memory-3"))
                .as("readTimeout=1s 时慢服务应触发读超时（超时值来自 DB timeout_seconds）")
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    // ==================== 用例 4：fail-fast（bean 不存在 / 方法不存在 / 非法 HTTP 方法） ====================

    @Test
    @DisplayName("用例4: beanName 不在容器 → NoSuchBeanDefinitionException；methodName 不存在 → IllegalStateException；非法 HTTP 方法 → IllegalArgumentException（构造阶段抛出）")
    void invalidConfig_shouldFailFast() {
        // 4a. beanName 不存在
        AgentToolInternalConfig ghostBean = internalConfig("ghost_tool", "幽灵 bean",
                null, "ghostBean", "execute");
        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of(ghostBean));
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("ghostBean")).thenThrow(new NoSuchBeanDefinitionException("ghostBean"));

        AgentToolCallbackFactory factory = new AgentToolCallbackFactory(internalMapper, externalMapper, ctx);
        assertThatThrownBy(() -> factory.buildToolCallbacks(null))
                .as("beanName 不在 ApplicationContext 时应于工厂构造阶段 fail-fast")
                .isInstanceOf(NoSuchBeanDefinitionException.class);

        // 4b. methodName 不存在（bean 无 execute(String) 方法）
        AgentToolInternalConfig noMethod = internalConfig("nomethod_tool", "无方法",
                null, "plainObjectBean", "execute");
        when(internalMapper.selectList(any())).thenReturn(List.of(noMethod));
        when(ctx.getBean("plainObjectBean")).thenReturn(new Object());
        assertThatThrownBy(() -> factory.buildToolCallbacks(null))
                .as("methodName 不存在时应于工厂构造阶段 fail-fast")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execute");

        // 4c. 外部工具非法 HTTP 方法
        AgentToolExternalConfig badMethod = externalConfig("bad_tool", "非法方法",
                null, "http://127.0.0.1:1/x", "DELETE", 5);
        when(internalMapper.selectList(any())).thenReturn(List.of());
        when(externalMapper.selectList(any())).thenReturn(List.of(badMethod));
        assertThatThrownBy(() -> factory.buildToolCallbacks(null))
                .as("仅支持 GET/POST/PUT，DELETE 应拒绝")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELETE");
    }

    // ==================== 用例 5：图 wiring（bindTools → ChatRequest 携带 toolSpecifications） ====================

    @Test
    @DisplayName("用例5: 绑定工具后 callModel 的 ChatRequest 携带 toolSpecifications 且含该工具；未绑定工具时不携带")
    void toolsBound_shouldCarrySpecsIntoRequest_unbound_shouldOmit() throws Exception {
        // 用工厂真实产出的句柄（内部工具，mock bean）验证全链路
        AgentToolInternalConfig config = internalConfig("sum_tool", "求和工具",
                null, "echoToolBean", "execute");
        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of(config));
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("echoToolBean")).thenReturn(new EchoToolBean());
        List<AgentToolCallbackFactory.ToolHandle> handles = new AgentToolCallbackFactory(internalMapper, externalMapper, ctx)
                .buildToolCallbacks(null);

        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        CapturingChatModel stub = new CapturingChatModel("工具回复");
        AgentGraphFactory.bindChatModel(stub);
        AgentGraphFactory.bindTools(handles);
        try {
            graph.invoke(Map.of("input", "hi", "chatModel", stub));
        } finally {
            AgentGraphFactory.clearChatModel();
            AgentGraphFactory.clearTools();
        }
        assertThat(stub.capturedRequest.toolSpecifications()).isNotNull();
        assertThat(stub.capturedRequest.toolSpecifications()).hasSize(1);
        assertThat(stub.capturedRequest.toolSpecifications().get(0).name()).isEqualTo("sum_tool");

        // 未绑定工具：ChatRequest 不携带 toolSpecifications（与无工具路径一致）
        CapturingChatModel plainStub = new CapturingChatModel("普通回复");
        AgentGraphFactory.bindChatModel(plainStub);
        try {
            graph.invoke(Map.of("input", "hi", "chatModel", plainStub));
        } finally {
            AgentGraphFactory.clearChatModel();
        }
        assertThat(plainStub.capturedRequest.toolSpecifications()).isNullOrEmpty();
    }

    // ==================== 用例 6：F04 工具调用记录捕获 ====================

    @Test
    @DisplayName("用例6: 绑定 TOOL_CALL_RECORDS_BINDING 后执行工具，ToolCallRecord（name/args/result/latencyMs）被捕获；未绑定时调用不抛异常")
    void toolCallRecord_shouldBeCapturedWhenBound() {
        AgentToolInternalConfig config = internalConfig("sum_tool", "求和工具",
                null, "echoToolBean", "execute");
        AgentToolInternalConfigMapper internalMapper = mock(AgentToolInternalConfigMapper.class);
        when(internalMapper.selectList(any())).thenReturn(List.of(config));
        AgentToolExternalConfigMapper externalMapper = mock(AgentToolExternalConfigMapper.class);
        when(externalMapper.selectList(any())).thenReturn(List.of());
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("echoToolBean")).thenReturn(new EchoToolBean());
        AgentToolCallbackFactory.ToolHandle handle = new AgentToolCallbackFactory(internalMapper, externalMapper, ctx)
                .buildToolCallbacks(100L).get(0);

        // 绑定记录载体（ServiceImpl invoke 前同款）→ 执行被包装计时并捕获
        List<ToolCallRecord> records = new ArrayList<>();
        AgentGraphFactory.bindToolCallRecords(records);
        try {
            String result = handle.executor().execute(
                    ToolExecutionRequest.builder().id("t6").name("sum_tool").arguments("\"hello\"").build(),
                    "memory-6");
            assertThat(result).contains("echo:");
            assertThat(records).hasSize(1);
            ToolCallRecord record = records.get(0);
            assertThat(record.getToolName()).isEqualTo("sum_tool");
            assertThat(record.getArgs()).isEqualTo("hello");
            assertThat(record.getResult()).contains("echo:");
            assertThat(record.getLatencyMs()).isGreaterThanOrEqualTo(0);
        } finally {
            AgentGraphFactory.clearToolCallRecords();
        }

        // 未绑定：调用不抛异常、不产生记录
        assertThat(handle.executor().execute(
                ToolExecutionRequest.builder().id("t6b").name("sum_tool").arguments("\"world\"").build(),
                "memory-6")).contains("echo:");
    }

    // ==================== 测试数据工厂 ====================

    private AgentToolInternalConfig internalConfig(String name, String description, String inputSchema,
                                                   String beanName, String methodName) {
        AgentToolInternalConfig config = new AgentToolInternalConfig();
        config.setName(name);
        config.setDescription(description);
        config.setInputSchema(inputSchema);
        config.setBeanName(beanName);
        config.setMethodName(methodName);
        config.setEnabled(true);
        return config;
    }

    private AgentToolExternalConfig externalConfig(String name, String description, String inputSchema,
                                                   String url, String httpMethod, Integer timeoutSeconds) {
        AgentToolExternalConfig config = new AgentToolExternalConfig();
        config.setName(name);
        config.setDescription(description);
        config.setInputSchema(inputSchema);
        config.setUrl(url);
        config.setHttpMethod(httpMethod);
        config.setTimeoutSeconds(timeoutSeconds);
        config.setEnabled(true);
        return config;
    }

    private HttpServer startServer(ServerHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            handler.handle(exchange, body);
        });
        s.start();
        return s;
    }

    /** 白名单内部工具 mock bean：约定签名 String execute(String params) */
    static class EchoToolBean {
        public String execute(String params) {
            return "echo:" + params;
        }
    }

    /** 记录收到的 ChatRequest 的 ChatModel 桩 */
    static class CapturingChatModel implements ChatModel {
        private final String reply;
        ChatRequest capturedRequest;

        CapturingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            this.capturedRequest = request;
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    @FunctionalInterface
    private interface ServerHandler {
        void handle(HttpExchange exchange, String body) throws IOException;
    }
}
