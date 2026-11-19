package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * TOOL 节点执行（自 AgentGraphInterpreter 拆出，行为不变）：
 * config.toolName → 白名单装载结果中按名称精确匹配单个工具回调 → 以入参文本
 * （inputVar 变量值，由调用方解析）为入参直接调用 → 返回解码后的纯文本
 * （由调用方写入 outputVar 指定变量）。
 * <p>
 * 与 F01 的区别：F01 把全部启用工具注入 LLM 由模型自行决定；本节点由图的拓扑
 * 决定调用哪个工具，只定位这一个回调并直接调用。每次执行即时装载（工厂非启动
 * 缓存语义：白名单配置变更即时生效）。
 * </p>
 */
class AgentGraphToolNodeRunner {

    private final AgentToolCallbackFactory toolCallbackFactory;

    /** 工具白名单装载的租户过滤条件（null 时不显式过滤，由 MyBatis-Plus 租户拦截器隔离） */
    private final Long tenantId;

    AgentGraphToolNodeRunner(AgentToolCallbackFactory toolCallbackFactory, Long tenantId) {
        this.toolCallbackFactory = toolCallbackFactory;
        this.tenantId = tenantId;
    }

    /**
     * TOOL 节点执行：config.toolName → 白名单装载结果中按名称精确匹配单个
     * {@code ToolCallback} → 直接调用 → 返回文本。
     */
    String callToolNode(GraphElement node, String text) {
        String toolName = AgentGraphNodeConfigs.requireConfigString(node, AgentGraphInterpreter.CONFIG_KEY_TOOL_NAME);
        if (toolCallbackFactory == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                    "工具工厂未装配，无法执行 TOOL 节点: " + toolName);
        }
        AgentToolCallbackFactory.ToolHandle target = toolCallbackFactory.buildToolCallbacks(tenantId).stream()
                .filter(handle -> toolName.equals(handle.specification().name()))
                .findFirst()
                .orElseThrow(() -> new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "TOOL 节点引用的工具不存在或未启用: " + toolName));
        // 工具回调契约（与 F01 一致，工厂回执 §3 实测）：FunctionToolCallback 的 call()
        // 入参为 JSON 字符串字面量（LLM 按 {"type":"string"} schema 发送），白名单方法
        // 收到 JSON 编码字符串；返回值同样经 JSON 编码（实测 "echo:你好" → "\"echo:你好\""）。
        // 解释器把累积文本 JSON 编码后传入，并把返回的编码文本解码还原为纯文本后覆盖
        // 累积文本（图执行上下文是给下游节点/最终输出使用的用户可读文本，与 F01 中
        // LLM 直接消费编码文本的用途不同）。
        try {
            // 执行器契约：arguments 为 JSON 编码字符串（与旧 FunctionToolCallback 的
            // call(JsonParser.toJson(text)) 语义一致），返回值经 JSON 编码
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("graph-tool-" + node.getId())
                    .name(toolName)
                    .arguments(AgentGraphTraceRecorder.JSON.writeValueAsString(text))
                    .build();
            String result = target.executor().execute(request, null);
            String decoded = AgentGraphLlmNodeRunner.decodeIfJsonString(result);
            if (decoded == null) {
                throw new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "TOOL 节点未返回文本: " + node.getId());
            }
            return decoded;
        } catch (AgentGraphInterpreter.GraphExecutionException e) {
            throw e;
        } catch (Exception e) {
            // 第三方异常（工具执行抛错等）：包装为带分类的图执行异常（Step12 错误分类维度）
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED,
                    e.getMessage(), e);
        }
    }
}
