package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图拓扑导航与后继路由（自 AgentGraphInterpreter 拆出，行为不变）。
 * <p>
 * 承载 elements 内的节点/边定位（START 查找、按 id 定位、出入边统计）与下一节点
 * 选路：CONDITION 节点按 §2-C 关键词子串匹配（elements 原始顺序即优先级，不排序），
 * 其余节点取唯一出边；另含 G1-4 必填入参校验。方法均为无状态静态工具。
 * </p>
 */
final class AgentGraphRouting {

    private AgentGraphRouting() {
    }

    /** 定位唯一 START 节点（执行前校验已保证唯一，此处防御性兜底） */
    static GraphElement findStart(List<GraphElement> elements) {
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind())
                    && AgentGraphInterpreter.NODE_TYPE_START.equals(element.getType())) {
                return element;
            }
        }
        throw new AgentGraphInterpreter.GraphExecutionException(
                AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID, "图中不存在 START 节点");
    }

    /** 按 id 定位节点（防御：边引用了不存在的节点） */
    static GraphElement findNode(String id, List<GraphElement> elements) {
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind()) && id.equals(element.getId())) {
                return element;
            }
        }
        throw new AgentGraphInterpreter.GraphExecutionException(
                AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID, "边引用了不存在的节点: " + id);
    }

    /** 当前节点的出边列表（kind=edge 且 source == 节点 id），按 elements 出现顺序 */
    static List<GraphElement> outgoingEdges(GraphElement node, List<GraphElement> elements) {
        List<GraphElement> edges = new ArrayList<>();
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getSource())) {
                edges.add(element);
            }
        }
        return edges;
    }

    /** 当前节点的入边数（JOIN 静态入边数 = 汇合分支数），按 target == 节点 id 统计 */
    static int incomingEdgeCount(GraphElement node, List<GraphElement> elements) {
        int count = 0;
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getTarget())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 确定下一节点 id：CONDITION 节点按 §2-C 关键词子串匹配（elements 原始顺序即优先级，
     * 不排序），匹配文本 = CONDITION 节点 config.{@code inputVar} 指定的变量值（缺失/
     * 空白 = 默认变量，Step10 语义）；其余节点（含 LOOP/JOIN，出边唯一约束保持生效）
     * 取唯一出边。
     *
     * @throws AgentGraphInterpreter.GraphExecutionException 条件无匹配且无默认边 / 默认边不唯一 / 出边数量非法 /
     *                                 未定义变量引用
     */
    static String nextNodeId(GraphElement current, List<GraphElement> elements,
                             Map<String, String> variables) {
        List<GraphElement> edges = outgoingEdges(current, elements);
        if (AgentGraphInterpreter.NODE_TYPE_CONDITION.equals(current.getType())) {
            // 匹配文本 = CONDITION 节点 inputVar 指定的变量值（缺失 = 默认变量）
            String matchText = AgentGraphVariables.readInput(current, variables);
            // 按 elements 出现顺序逐条匹配关键词，取第一个命中（不排序，原始顺序即优先级）
            for (GraphElement edge : edges) {
                String keyword = AgentGraphNodeConfigs.keywordOf(edge);
                if (keyword != null && matchText.contains(keyword)) {
                    return edge.getTarget();
                }
            }
            // 未命中 → 唯一无 keyword 边为默认边；默认边不存在 → 图设计缺陷，不静默吞掉
            List<GraphElement> defaultEdges = edges.stream()
                    .filter(e -> AgentGraphNodeConfigs.keywordOf(e) == null)
                    .toList();
            if (defaultEdges.size() == 1) {
                return defaultEdges.get(0).getTarget();
            }
            if (defaultEdges.isEmpty()) {
                throw new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_CONDITION_NO_MATCH,
                        "条件分支无匹配且无默认边: " + current.getId());
            }
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "条件分支默认边不唯一: " + current.getId());
        }
        // 非条件节点：必须且只能有一条出边（START/END/LLM/TOOL/LOOP/JOIN；FORK 不
        // 经此方法，由 run() 路由段按全部出边扇出）
        if (edges.isEmpty()) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点没有出边，无法继续执行: " + current.getId());
        }
        if (edges.size() > 1) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "非条件节点的出边不唯一: " + current.getId());
        }
        return edges.get(0).getTarget();
    }

    /**
     * G1-4 必填入参校验：开始节点 config.params 中 required=true 的参数必须在
     * {@code inputs} 中存在且非空白；缺失抛 INPUT_MISSING 并列出全部缺失参数名。
     * 未声明参数（旧图）= 不校验，行为不变。
     */
    static void validateRequiredInputs(List<GraphElement> elements, Map<String, String> inputs) {
        GraphElement start;
        try {
            start = findStart(elements);
        } catch (AgentGraphInterpreter.GraphExecutionException noStart) {
            return; // 拓扑错误由执行前校验/主循环统一报出
        }
        Map<String, Object> config = start.getConfig();
        if (config == null) return;
        Object raw = config.get(AgentGraphInterpreter.CONFIG_KEY_INPUT_PARAMS);
        if (!(raw instanceof List<?> params)) return;
        List<String> missing = new ArrayList<>();
        for (Object item : params) {
            if (!(item instanceof Map<?, ?> param)) continue;
            boolean required = Boolean.TRUE.equals(param.get("required"));
            if (!required) continue;
            Object nameValue = param.get("name");
            String name = nameValue == null ? "" : String.valueOf(nameValue).trim();
            if (name.isEmpty()) continue;
            String value = inputs == null ? null : inputs.get(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_INPUT_MISSING,
                    "缺少必填参数: " + String.join(", ", missing));
        }
    }
}
