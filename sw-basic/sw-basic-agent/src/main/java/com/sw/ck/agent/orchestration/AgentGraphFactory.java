package com.sw.ck.agent.orchestration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小编排图工厂（M07 Step2 → F1 阶段 1 LangChain4j 化）：单节点
 * {@code StateGraph<AgentState>}，{@code START → callModel → END}，节点内调用一次
 * 模型并提取回复文本；绑定工具时在节点内完成"tool_calls → 本地执行 → 回喂"循环
 * （LangChain4j 低层 {@link ChatModel} 不内建工具执行，方向文件阶段 2 要求同款语义）。
 * <p>
 * ThreadLocal 绑定模式不变（chatModel / 工具 / 历史消息 / 工具调用记录 / Token 用量）：
 * LangGraph4j 节点间 state 深拷贝不可序列化 ChatModel，由 {@link SparseStateSerializer}
 * 跳过并从线程绑定重新挂载。
 * </p>
 */
public class AgentGraphFactory {

    /** 模型调用节点名 */
    public static final String NODE_CALL_MODEL = "callModel";

    /** 工具循环上限（防 LLM 连续索取工具不收敛） */
    private static final int MAX_TOOL_ROUNDS = 16;

    private static final ThreadLocal<ChatModel> CHAT_MODEL_BINDING = new ThreadLocal<>();
    private static final ThreadLocal<List<AgentToolCallbackFactory.ToolHandle>> TOOLS_BINDING = new ThreadLocal<>();
    private static final ThreadLocal<List<ChatMessage>> HISTORY_MESSAGES_BINDING = new ThreadLocal<>();
    static final ThreadLocal<List<ToolCallRecord>> TOOL_CALL_RECORDS_BINDING = new ThreadLocal<>();
    private static final ThreadLocal<UsageSnapshot> TOKEN_USAGE_BINDING = new ThreadLocal<>();

    /** 绑定本次执行的 ChatModel（invoke 前调用） */
    public static void bindChatModel(ChatModel chatModel) {
        CHAT_MODEL_BINDING.set(chatModel);
    }

    /** 清除本次执行的 ChatModel 绑定（invoke 结束后 finally 调用） */
    public static void clearChatModel() {
        CHAT_MODEL_BINDING.remove();
    }

    /** 绑定本次执行的工具句柄列表（invoke 前调用；空列表时无效果，行为同未绑定） */
    public static void bindTools(List<AgentToolCallbackFactory.ToolHandle> tools) {
        TOOLS_BINDING.set(tools);
    }

    /** 清除本次执行的工具绑定（invoke 结束后 finally 调用，防 ThreadLocal 泄漏） */
    public static void clearTools() {
        TOOLS_BINDING.remove();
    }

    /** 绑定本次执行的历史消息（invoke 前调用；null/空列表时行为与无历史一致） */
    public static void bindHistoryMessages(List<ChatMessage> messages) {
        HISTORY_MESSAGES_BINDING.set(messages);
    }

    /** 清除历史消息绑定（invoke 结束后 finally 调用） */
    public static void clearHistoryMessages() {
        HISTORY_MESSAGES_BINDING.remove();
    }

    /** 绑定工具调用记录载体（invoke 前调用，由 ServiceImpl 传入空列表） */
    public static void bindToolCallRecords(List<ToolCallRecord> records) {
        TOOL_CALL_RECORDS_BINDING.set(records);
    }

    /** 清除工具调用记录载体（invoke 结束后 finally 调用） */
    public static void clearToolCallRecords() {
        TOOL_CALL_RECORDS_BINDING.remove();
    }

    /** 读取本次执行捕获的工具调用记录（未绑定返回 null） */
    public static List<ToolCallRecord> getToolCallRecords() {
        return TOOL_CALL_RECORDS_BINDING.get();
    }

    /** Token 使用量快照（input/output 独立 null 语义：供应商未返回保持 null，不写零） */
    public record UsageSnapshot(Long inputTokens, Long outputTokens) {}

    /** 存储 Token 使用量（callModel 节点调用） */
    static void storeTokenUsage(TokenUsage usage) {
        TOKEN_USAGE_BINDING.set(new UsageSnapshot(
                usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount().longValue() : null,
                usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount().longValue() : null));
    }

    /** 清除 Token 使用量（invoke 结束后 finally 调用） */
    public static void clearTokenUsage() {
        TOKEN_USAGE_BINDING.remove();
    }

    /** 读取本次执行的 Token 使用量（未绑定返回 null） */
    public static UsageSnapshot getTokenUsage() {
        return TOKEN_USAGE_BINDING.get();
    }

    /**
     * 构造并编译最小图。
     *
     * @throws GraphStateException 图构造/编译失败（节点重名、边非法等）
     */
    public CompiledGraph<AgentState> buildGraph() throws GraphStateException {
        Map<String, Channel<?>> channels = Map.of(
                "input", Channels.base(() -> ""),
                "output", Channels.base(() -> ""),
                "chatModel", Channels.base((Object prev, Object next) -> next));
        StateGraph<AgentState> graph = new StateGraph<>(channels, new SparseStateSerializer());
        graph.addNode(NODE_CALL_MODEL, AsyncNodeAction.node_async(this::callModel));
        graph.addEdge(StateGraph.START, NODE_CALL_MODEL);
        graph.addEdge(NODE_CALL_MODEL, StateGraph.END);
        return graph.compile();
    }

    /**
     * 节点动作：取 chatModel 与 input → 调用模型（绑定工具时执行工具循环）→ 输出文本。
     */
    private Map<String, Object> callModel(AgentState state) throws Exception {
        ChatModel chatModel = (ChatModel) state.value("chatModel")
                .orElseThrow(() -> new IllegalStateException("初始状态缺少 chatModel"));
        String input = (String) state.value("input")
                .orElseThrow(() -> new IllegalStateException("初始状态缺少 input"));
        List<AgentToolCallbackFactory.ToolHandle> tools = TOOLS_BINDING.get();
        List<ChatMessage> history = HISTORY_MESSAGES_BINDING.get();

        List<ChatMessage> messages = new ArrayList<>();
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(UserMessage.from(input));

        List<ToolSpecification> specs = new ArrayList<>();
        if (tools != null) {
            for (AgentToolCallbackFactory.ToolHandle handle : tools) {
                specs.add(handle.specification());
            }
        }
        ChatResponse response;
        int rounds = 0;
        while (true) {
            ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
            if (!specs.isEmpty()) {
                builder.toolSpecifications(specs);
            }
            response = chatModel.chat(builder.build());
            AiMessage ai = response.aiMessage();
            messages.add(ai);
            if (!ai.hasToolExecutionRequests() || tools == null || tools.isEmpty()) {
                break;
            }
            if (++rounds > MAX_TOOL_ROUNDS) {
                throw new IllegalStateException("工具调用轮数超过上限 " + MAX_TOOL_ROUNDS);
            }
            for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                ToolExecutor executor = AgentToolCallbackFactory.executorOf(tools, request.name());
                String result;
                if (executor == null) {
                    result = "工具不存在: " + request.name();
                } else {
                    try {
                        result = executor.execute(request, null);
                    } catch (RuntimeException e) {
                        // 工具执行失败回喂错误消息，不中断调用（与旧 Spring AI 语义一致）
                        result = "工具执行失败: " + e.getMessage();
                    }
                }
                messages.add(ToolExecutionResultMessage.from(request, result));
            }
        }
        String output = response.aiMessage().text();
        storeTokenUsage(response.metadata() != null ? response.metadata().tokenUsage() : null);
        return Map.of("output", output != null ? output : "");
    }

    /**
     * 稀疏状态序列化器：LangGraph4j 在节点间对 state 做深拷贝，只序列化
     * input/output；chatModel 在 write 时跳过、read 时从线程绑定重新挂载。
     */
    static class SparseStateSerializer extends StateSerializer<AgentState> {

        SparseStateSerializer() {
            super(AgentState::new);
        }

        @Override
        public void write(AgentState object, ObjectOutput out) throws IOException {
            out.writeObject(object.value("input").orElse(""));
            out.writeObject(object.value("output").orElse(""));
        }

        @Override
        public AgentState read(ObjectInput in) throws IOException, ClassNotFoundException {
            Map<String, Object> data = new HashMap<>();
            data.put("input", in.readObject());
            data.put("output", in.readObject());
            ChatModel bound = CHAT_MODEL_BINDING.get();
            if (bound != null) {
                data.put("chatModel", bound);
            }
            return stateOf(data);
        }
    }
}
