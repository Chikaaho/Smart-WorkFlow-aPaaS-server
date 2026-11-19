package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.common.exception.BaseException;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 节点执行（自 AgentGraphInterpreter 拆出，行为不变）：
 * config.agentModelConfigId → 经 {@link AgentModelRuntimeService} 单跳调用（启停校验 +
 * LangChain4j 单例复用 + 密钥轮换），按 config.systemPrompt / config.userPromptTemplate
 * 组装消息列表；节点可挂工具（config.toolNames），模型请求工具时在节点内完成工具调用
 * 循环，直到模型给出最终文本或轮次超限。
 */
class AgentGraphLlmNodeRunner {

    /** 模型运行时（LangChain4j 装配 + 单例复用 + 启停校验，F1 阶段 1） */
    private final AgentModelRuntimeService modelRuntimeService;

    private final AgentToolCallbackFactory toolCallbackFactory;

    /** 工具白名单装载的租户过滤条件（null 时不显式过滤，由 MyBatis-Plus 租户拦截器隔离） */
    private final Long tenantId;

    AgentGraphLlmNodeRunner(AgentModelRuntimeService modelRuntimeService,
                            AgentToolCallbackFactory toolCallbackFactory,
                            Long tenantId) {
        this.modelRuntimeService = modelRuntimeService;
        this.toolCallbackFactory = toolCallbackFactory;
        this.tenantId = tenantId;
    }

    /**
     * LLM 节点执行：config.agentModelConfigId → {@code ChatModelFactory.build}
     * → 按 config.systemPrompt / config.userPromptTemplate 组装消息列表单跳调用（不带工具、
     * 不带历史）→ 返回输出（由调用方写入 outputVar 指定变量）。
     * <p>
     * <b>Prompt 契约（M07-F02-02 新增）</b>：
     * <ul>
     *   <li>config.systemPrompt（可选）：空白 = 不注入 SystemMessage；非空白整体作为
     *       SystemMessage（不做变量插值——系统 Prompt 用于角色/规则/背景，与变量无关）</li>
     *   <li>config.userPromptTemplate（可选）：空白/缺失 = 退化为 inputVar 原文（历史行为）；
     *       配置后按 {{variableName}} 做一次性纯字符串插值；未定义变量抛 UNDEFINED_VARIABLE；
     *       变量值按普通文本替换，不再次解析其中的 {{...}}（避免二次展开 / 表达式注入）</li>
     * </ul>
     * 历史图（无 systemPrompt/userPromptTemplate）行为 = 仅 UserMessage(inputVar 值)，
     * 与 Step8/10/11/12 完全一致（零迁移）。
     *
     * @param node      当前 LLM 节点
     * @param text      inputVar 读出的原始值（未配置 userPromptTemplate 时直接作为用户消息）
     * @param variables 当前变量表（供 userPromptTemplate 插值使用）
     * @param traces    本次执行的节点轨迹列表（节点真实输入写入最新一条轨迹）
     */
    String callLlmNode(GraphElement node, String text, Map<String, String> variables,
                       List<AgentGraphInterpreter.NodeExecutionTrace> traces) {
        Long modelConfigId = AgentGraphNodeConfigs.requireConfigId(node,
                AgentGraphInterpreter.CONFIG_KEY_AGENT_MODEL_CONFIG_ID);
        try {
            // F1 阶段 1：经运行时服务解析（启停校验 + LangChain4j 单例复用 + 模板装配；
            // D022-05：429 时组内密钥轮换，与编排入口同语义）
            // === Prompt 契约组装 ===
            String systemPrompt = AgentGraphNodeConfigs.configString(node, AgentGraphInterpreter.CONFIG_KEY_SYSTEM_PROMPT);
            String userPromptTemplate = AgentGraphNodeConfigs.configString(node, AgentGraphInterpreter.CONFIG_KEY_USER_PROMPT_TEMPLATE);
            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            String userText;
            if (userPromptTemplate == null || userPromptTemplate.isBlank()) {
                // 历史行为：直接用 inputVar 值作为用户消息（旧图零迁移关键）
                userText = text;
            } else {
                userText = AgentGraphVariables.interpolateTemplate(userPromptTemplate, node, variables);
            }
            messages.add(UserMessage.from(userText));
            AgentGraphInterpreter.NodeExecutionTrace currentTrace = traces.get(traces.size() - 1);
            // F1S2-G1：节点真实输入（模板插值后的用户消息）
            currentTrace.setInputText(userText);
            // 阶段 2：节点可挂工具，模型请求工具时在节点内完成工具调用循环
            return executeChatWithTools(modelRuntimeService, toolCallbackFactory, tenantId,
                    node, modelConfigId, messages, currentTrace);
        } catch (AgentGraphInterpreter.GraphExecutionException e) {
            // 自有异常（未返回文本等）已带分类，原样上抛
            throw e;
        } catch (BaseException e) {
            // 运行时服务拒绝（模型/供应商停用或不存在）：归入模型调用失败分类
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_MODEL_CALL_FAILED,
                    e.getMessage(), e);
        } catch (Exception e) {
            // 第三方异常（解密失败/模型网络超时/429/未识别响应等）：包装为带分类的
            // 图执行异常（Step12 错误分类维度），message 沿用 cause 最深非空文本，
            // errorMessage 摘要语义不变
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_MODEL_CALL_FAILED,
                    e.getMessage(), e);
        }
    }

    /**
     * 模型调用 + 节点内工具循环（阶段 2，图执行与单步调试共用）：
     * 按 config.toolNames 取启用工具规格随请求下发；模型返回工具请求时逐个执行并把
     * 结果回喂模型，直到模型给出最终文本或轮次超限。每次工具调用的入参与输出写入
     * 当前节点轨迹。
     */
    static String executeChatWithTools(AgentModelRuntimeService runtime,
                                       AgentToolCallbackFactory factory,
                                       Long tenantId,
                                       GraphElement node,
                                       Long modelConfigId,
                                       List<ChatMessage> messages,
                                       AgentGraphInterpreter.NodeExecutionTrace trace) {
        List<String> toolNames = AgentGraphNodeConfigs.configStringList(node, AgentGraphInterpreter.CONFIG_KEY_TOOL_NAMES);
        List<AgentToolCallbackFactory.ToolHandle> handles = new ArrayList<>();
        if (!toolNames.isEmpty()) {
            if (factory == null) {
                throw new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "工具工厂未装配，无法执行 LLM 节点工具: " + node.getId());
            }
            List<AgentToolCallbackFactory.ToolHandle> available = factory.buildToolCallbacks(tenantId);
            for (String toolName : toolNames) {
                AgentToolCallbackFactory.ToolHandle handle = available.stream()
                        .filter(h -> toolName.equals(h.specification().name()))
                        .findFirst()
                        .orElseThrow(() -> new AgentGraphInterpreter.GraphExecutionException(
                                AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                                "LLM 节点引用的工具不存在或未启用: " + toolName));
                handles.add(handle);
            }
        }
        List<ToolSpecification> specs = handles.stream()
                .map(AgentToolCallbackFactory.ToolHandle::specification)
                .toList();
        int maxRounds = AgentGraphNodeConfigs.configInt(node, AgentGraphInterpreter.CONFIG_KEY_MAX_TOOL_ROUNDS,
                AgentGraphInterpreter.DEFAULT_MAX_TOOL_ROUNDS);
        long inputTokens = 0L;
        long outputTokens = 0L;
        boolean inputSeen = false;
        boolean outputSeen = false;
        int rounds = 0;
        while (true) {
            // 增量回调在 langchain4j 传输线程触发，读取线程绑定上下文取不到生命周期监听器；
            // 在解释器线程先捕获监听器，增量到达时直接投递，避免线程本地查找静默丢失。
            final AgentExecutionEventContext.Listener lifecycleListener =
                    AgentExecutionEventContext.get();
            ChatResponse response = lifecycleListener == null
                    ? runtime.chatWithRotation(modelConfigId, messages, specs)
                    : runtime.chatStreamingWithRotation(modelConfigId, messages, specs,
                            delta -> lifecycleListener.accept("MODEL_DELTA", null, delta));
            TokenUsage usage =
                    response.metadata() != null ? response.metadata().tokenUsage() : null;
            if (usage != null && usage.inputTokenCount() != null) {
                inputTokens += usage.inputTokenCount();
                inputSeen = true;
            }
            if (usage != null && usage.outputTokenCount() != null) {
                outputTokens += usage.outputTokenCount();
                outputSeen = true;
            }
            var ai = response.aiMessage();
            if (ai == null) {
                throw new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_MODEL_CALL_FAILED,
                        "LLM 节点未返回消息: " + node.getId());
            }
            if (!ai.hasToolExecutionRequests()) {
                String output = ai.text();
                if (output == null) {
                    throw new AgentGraphInterpreter.GraphExecutionException(
                            AgentGraphInterpreter.ERROR_CATEGORY_MODEL_CALL_FAILED,
                            "LLM 节点未返回文本: " + node.getId());
                }
                trace.setInputTokens(inputSeen ? inputTokens : null);
                trace.setOutputTokens(outputSeen ? outputTokens : null);
                // F1S2-G1：节点真实输出 = 模型最终回答（含工具循环后的收敛结果）
                trace.setOutputText(output);
                return output;
            }
            if (++rounds > maxRounds) {
                throw new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "工具调用轮次超过上限 " + maxRounds + ": " + node.getId());
            }
            messages.add(ai);
            for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                AgentToolCallbackFactory.ToolHandle handle = handles.stream()
                        .filter(h -> request.name().equals(h.specification().name()))
                        .findFirst()
                        .orElseThrow(() -> new AgentGraphInterpreter.GraphExecutionException(
                                AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                                "模型请求了未挂载的工具: " + request.name()));
                String result;
                try {
                    result = handle.executor().execute(request, null);
                } catch (Exception e) {
                    // 工具执行失败回喂错误文本，让模型自行收敛；不中断整个节点
                    result = "工具执行失败: " + e.getMessage();
                }
                String decoded = decodeIfJsonString(result);
                trace.addToolCall(new AgentGraphInterpreter.ToolCallTrace(request.name(), request.arguments(), decoded));
                messages.add(ToolExecutionResultMessage
                        .from(request, decoded));
            }
        }
    }

    /**
     * 工具返回解码：JSON 字符串字面量（如 {@code "hello"}）解码还原为纯文本；
     * 非 JSON 字符串（如外部工具返回的裸文本/JSON 对象）原样保留，不做转换。
     */
    static String decodeIfJsonString(String result) {
        if (result == null) {
            return null;
        }
        try {
            return AgentGraphTraceRecorder.JSON.readValue(result, String.class);
        } catch (Exception e) {
            return result;
        }
    }
}
