package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图执行多变量上下文读写工具（自 AgentGraphInterpreter 拆出，行为不变，Step10）。
 * <p>
 * 命名变量表 {@code Map<String, String>} 的读取/写入/模板插值：节点经 config.
 * {@code inputVar} / {@code outputVar} 指定变量名，缺失/空白 = 默认变量
 * {@value #DEFAULT_VARIABLE_NAME}（旧图零迁移锚点）；未定义变量引用为运行时错误。
 * 写入时同步记录节点作用域输出表（G1-4 END 输出声明解析用）。
 * </p>
 */
final class AgentGraphVariables {

    /** 默认变量名（旧图语义锚点），与 {@link AgentGraphInterpreter#DEFAULT_VARIABLE_NAME} 同值。 */
    static final String DEFAULT_VARIABLE_NAME = AgentGraphInterpreter.DEFAULT_VARIABLE_NAME;

    /**
     * 用户 Prompt 模板占位符正则：{{variableName}}，变量名按 Java 标识符规则（首字
     * 母/下划线，后续字母数字下划线），一次性纯字符串替换（不做二次展开，避免变量值注入）。
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}");

    private AgentGraphVariables() {
    }

    /**
     * 读取节点输入文本：config.{@code inputVar} 指定的变量值（缺失/空白 = 默认变量）。
     *
     * @throws AgentGraphInterpreter.GraphExecutionException 变量未定义（从未写入且非默认变量）——未定义
     *                                 变量引用为运行时错误，不做执行前数据流静态校验（方向文档已确认）
     */
    static String readInput(GraphElement node, Map<String, String> variables) {
        return readVariable(node, variables, AgentGraphInterpreter.CONFIG_KEY_INPUT_VAR);
    }

    /**
     * 按变量名键读取变量值：config 键缺失/空白 = 默认变量；变量不存在抛运行时错误。
     * 默认变量恒存在（run 开头写入请求入参），因此旧图（无变量名字段）永不触发。
     */
    static String readVariable(GraphElement node, Map<String, String> variables, String varKey) {
        String varName = resolveVarName(node, varKey);
        String value = variables.get(varName);
        if (value == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_UNDEFINED_VARIABLE,
                    "引用了未定义的变量: " + varName + "（节点 " + node.getId() + "）");
        }
        return value;
    }

    /**
     * 写入节点输出：config.{@code outputVar} 指定结果写入的变量（缺失/空白 = 默认变量，
     * 覆盖语义与 Step8 单文本版一致；指定新变量名 = 创建变量）。LLM/TOOL 结果恒为
     * 非空文本，变量值类型全为 String（非文本类型不在本 Step 范围）。
     */
    static void writeOutput(GraphElement node, Map<String, String> variables, String output,
                            Map<String, Map<String, String>> nodeOutputs) {
        String varName = resolveVarName(node, AgentGraphInterpreter.CONFIG_KEY_OUTPUT_VAR);
        variables.put(varName, output);
        // G1-4：节点作用域输出留档（END 输出声明 source = nodeId.var 解析用；
        // 全局变量表仍是最后写入覆盖语义，作用域表按节点独立记录）
        nodeOutputs.computeIfAbsent(node.getId(), key -> new HashMap<>()).put(varName, output);
    }

    /**
     * 解析节点 config 中的变量名键：config 缺失 / 键缺失 / 值非 String / 空白 → 默认
     * 变量（与 {@link AgentGraphNodeConfigs#keywordOf} 同款宽松语义：旧图无变量名字段即落默认变量，零迁移）。
     */
    private static String resolveVarName(GraphElement node, String varKey) {
        Map<String, Object> config = node.getConfig();
        if (config == null) {
            return DEFAULT_VARIABLE_NAME;
        }
        Object value = config.get(varKey);
        if (!(value instanceof String s) || s.isBlank()) {
            return DEFAULT_VARIABLE_NAME;
        }
        return s;
    }

    /**
     * 对模板中的 {@code {{variableName}}} 占位符做一次性纯字符串插值（M07-F02-02）。
     * 变量名按 Java 标识符规则（{@code [A-Za-z_][A-Za-z0-9_]*}）匹配；未定义变量抛
     * {@link AgentGraphInterpreter.GraphExecutionException}（分类
     * {@link AgentGraphInterpreter#ERROR_CATEGORY_UNDEFINED_VARIABLE}）；
     * 变量值按普通文本替换（{@link Matcher#quoteReplacement}），不再次解析其中的
     * {@code {{...}}}，避免二次展开 / 表达式注入。
     * <p>
     * 不匹配占位符模式的文本（如 {@code "{{ invalid name }}"} 含空格 /
     * {@code "{{123numeric}}"}} 非标识符开头）原样保留，不抛错。
     */
    static String interpolateTemplate(String template, GraphElement node,
                                      Map<String, String> variables) {
        Matcher m = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (m.find()) {
            String varName = m.group(1);
            String value = variables.get(varName);
            if (value == null) {
                throw new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_UNDEFINED_VARIABLE,
                        "引用了未定义的变量: " + varName + "（节点 " + node.getId() + "）");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
