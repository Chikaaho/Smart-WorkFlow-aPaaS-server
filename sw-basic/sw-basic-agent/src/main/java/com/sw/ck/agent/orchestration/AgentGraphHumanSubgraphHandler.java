package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工（HUMAN）节点与子图（SUBGRAPH）节点执行（自 AgentGraphInterpreter 拆出，
 * 行为不变，G3）：无决策 → 构造可序列化暂停状态抛人工暂停异常（由生命周期服务
 * 落库等待）；有决策 → 应用填写内容并按按钮关键词路由。子图节点调用固定版本子图
 * 并按映射回写父变量，子图内人工暂停沿暂停链上抛（G3-5）。
 */
final class AgentGraphHumanSubgraphHandler {

    private AgentGraphHumanSubgraphHandler() {
    }

    static AgentGraphInterpreter.HumanDecision findDecision(AgentGraphInterpreter.RunOptions options, String nodeId) {
        if (options == null || options.decisions == null) return null;
        for (AgentGraphInterpreter.HumanDecision decision : options.decisions) {
            if (decision != null && nodeId.equals(decision.nodeId)) return decision;
        }
        return null;
    }

    /** HUMAN 节点执行：无决策 → 构造暂停状态抛 HumanPauseException；有决策 → 应用填写内容。 */
    static void executeHumanNode(GraphElement node, Map<String, String> variables,
                                 AgentGraphInterpreter.RunOptions options,
                                 AgentGraphInterpreter.NodeExecutionTrace trace,
                                 List<ActiveExecutionPoint> activePoints,
                                 ActiveExecutionPoint point,
                                 Map<String, Integer> loopCounts,
                                 Map<String, Integer> joinCounts) {
        AgentGraphInterpreter.HumanDecision decision = findDecision(options, node.getId());
        if (decision == null) {
            trace.setStatus("WAITING");
            AgentGraphInterpreter.PauseState state = new AgentGraphInterpreter.PauseState();
            state.activePoints.add(new AgentGraphInterpreter.ActivePointState(point.nodeId, point.branchPath));
            for (ActiveExecutionPoint remaining : activePoints) {
                state.activePoints.add(new AgentGraphInterpreter.ActivePointState(remaining.nodeId, remaining.branchPath));
            }
            state.variables = new HashMap<>(variables);
            state.loopCounts = new HashMap<>(loopCounts);
            state.joinCounts = new HashMap<>(joinCounts);
            throw new AgentGraphInterpreter.HumanPauseException(state, buildHumanTaskSpec(node, variables));
        }
        if (decision.values != null) {
            decision.values.forEach((key, value) -> {
                if (key != null && value != null) variables.put(key, value);
            });
        }
        trace.setInputText(decision.decision);
        trace.setOutputText(decision.decision);
    }

    /** HUMAN 节点路由：按钮 keyword = 出边关键词；TIMEOUT 走 timeoutKeyword；无匹配抛错。 */
    static String routeHumanNode(GraphElement node, AgentGraphInterpreter.HumanDecision decision,
                                 List<GraphElement> elements) {
        String keyword = decision == null || decision.decision == null ? "" : decision.decision;
        if (AgentGraphInterpreter.HUMAN_TIMEOUT_DECISION.equals(keyword)) {
            String timeoutKeyword = AgentGraphNodeConfigs.configString(node, AgentGraphInterpreter.CONFIG_KEY_TIMEOUT_KEYWORD);
            if (timeoutKeyword == null || timeoutKeyword.isBlank()) {
                throw new AgentGraphInterpreter.GraphExecutionException(
                        AgentGraphInterpreter.ERROR_CATEGORY_HUMAN_TIMEOUT,
                        "人工节点等待超时且未配置超时分支: " + node.getId());
            }
            keyword = timeoutKeyword;
        }
        GraphElement defaultEdge = null;
        for (GraphElement edge : AgentGraphRouting.outgoingEdges(node, elements)) {
            String edgeKeyword = AgentGraphNodeConfigs.keywordOf(edge);
            if (edgeKeyword == null || edgeKeyword.isBlank()) {
                if (defaultEdge == null) defaultEdge = edge;
                continue;
            }
            if (edgeKeyword.equals(keyword)) return edge.getTarget();
        }
        if (defaultEdge != null) return defaultEdge.getTarget();
        throw new AgentGraphInterpreter.GraphExecutionException(
                AgentGraphInterpreter.ERROR_CATEGORY_CONDITION_NO_MATCH,
                "人工节点无匹配决策分支: " + keyword + "（节点 " + node.getId() + "）");
    }

    /** 构造人工任务定义（说明按当前变量插值）。 */
    private static AgentGraphInterpreter.HumanTaskSpec buildHumanTaskSpec(GraphElement node, Map<String, String> variables) {
        AgentGraphInterpreter.HumanTaskSpec spec = new AgentGraphInterpreter.HumanTaskSpec();
        spec.humanNodeId = node.getId();
        String instructions = AgentGraphNodeConfigs.configString(node, AgentGraphInterpreter.CONFIG_KEY_INSTRUCTIONS);
        spec.instructions = instructions == null ? ""
                : AgentGraphVariables.interpolateTemplate(instructions, node, variables);
        spec.fields = AgentGraphNodeConfigs.configMapList(node, AgentGraphInterpreter.CONFIG_KEY_FIELDS);
        spec.buttons = AgentGraphNodeConfigs.configMapList(node, AgentGraphInterpreter.CONFIG_KEY_BUTTONS);
        spec.assigneeUserIds = AgentGraphNodeConfigs.configLongList(node, AgentGraphInterpreter.CONFIG_KEY_ASSIGNEE_USERS);
        spec.assigneeRoleCodes = AgentGraphNodeConfigs.configStringValues(node, AgentGraphInterpreter.CONFIG_KEY_ASSIGNEE_ROLES);
        spec.timeoutSeconds = AgentGraphNodeConfigs.configInteger(node, AgentGraphInterpreter.CONFIG_KEY_TIMEOUT_SECONDS);
        spec.timeoutKeyword = AgentGraphNodeConfigs.configString(node, AgentGraphInterpreter.CONFIG_KEY_TIMEOUT_KEYWORD);
        return spec;
    }

    /** SUBGRAPH 节点执行：调用子图（固定版本）并按映射回写父变量。 */
    static void executeSubgraphNode(GraphElement node, Map<String, String> variables,
                                    AgentGraphInterpreter.RunOptions options,
                                    AgentGraphInterpreter.NodeExecutionTrace trace,
                                    List<ActiveExecutionPoint> activePoints,
                                    ActiveExecutionPoint point,
                                    Map<String, Integer> loopCounts,
                                    Map<String, Integer> joinCounts) {
        if (options == null || options.subgraphRunner == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "子图运行器未配置: " + node.getId());
        }
        Long graphDefId = AgentGraphNodeConfigs.configLong(node, AgentGraphInterpreter.CONFIG_KEY_SUBGRAPH_ID);
        Integer version = AgentGraphNodeConfigs.configInteger(node, AgentGraphInterpreter.CONFIG_KEY_SUBGRAPH_VERSION);
        if (graphDefId == null || version == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "子图节点缺少 graphDefId/graphDefVersion: " + node.getId());
        }
        Map<String, String> subInputs = new LinkedHashMap<>();
        List<Map<String, Object>> inputMappings = AgentGraphNodeConfigs.configMapList(node, AgentGraphInterpreter.CONFIG_KEY_SUBGRAPH_INPUTS);
        if (!inputMappings.isEmpty()) {
            for (Map<String, Object> mapping : inputMappings) {
                String name = AgentGraphNodeConfigs.stringValue(mapping.get("name"));
                String source = AgentGraphNodeConfigs.stringValue(mapping.get("source"));
                if (name.isEmpty() || source.isEmpty()) continue;
                String value = variables.get(source);
                if (value == null) {
                    throw new AgentGraphInterpreter.GraphExecutionException(
                            AgentGraphInterpreter.ERROR_CATEGORY_UNDEFINED_VARIABLE,
                            "子图入参映射引用了未定义变量: " + source + "（节点 " + node.getId() + "）");
                }
                subInputs.put(name, value);
            }
        } else {
            subInputs.put(AgentGraphInterpreter.DEFAULT_VARIABLE_NAME,
                    variables.getOrDefault(AgentGraphInterpreter.DEFAULT_VARIABLE_NAME, ""));
        }
        AgentGraphInterpreter.SubgraphCall call = new AgentGraphInterpreter.SubgraphCall(graphDefId, version, subInputs);
        if (options.resumeState != null && options.resumeState.subgraphPauseStates != null) {
            call.resumeState = options.resumeState.subgraphPauseStates.get(node.getId());
        }
        call.decisions = options.decisions;
        AgentGraphInterpreter.SubgraphResult result;
        try {
            result = options.subgraphRunner.run(call);
        } catch (AgentGraphInterpreter.HumanPauseException childPause) {
            // G3-5：子图内人工节点暂停——父执行同样进入等待，保存子图暂停链
            trace.setStatus("WAITING");
            AgentGraphInterpreter.PauseState parentState = new AgentGraphInterpreter.PauseState();
            parentState.activePoints.add(new AgentGraphInterpreter.ActivePointState(point.nodeId, point.branchPath));
            for (ActiveExecutionPoint remaining : activePoints) {
                parentState.activePoints.add(new AgentGraphInterpreter.ActivePointState(remaining.nodeId, remaining.branchPath));
            }
            parentState.variables = new HashMap<>(variables);
            parentState.loopCounts = new HashMap<>(loopCounts);
            parentState.joinCounts = new HashMap<>(joinCounts);
            if (options.resumeState != null && options.resumeState.subgraphPauseStates != null) {
                parentState.subgraphPauseStates.putAll(options.resumeState.subgraphPauseStates);
            }
            parentState.subgraphPauseStates.put(node.getId(), childPause.getPauseState());
            throw new AgentGraphInterpreter.HumanPauseException(parentState, childPause.getTaskSpec());
        }
        if (result == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "子图运行无返回: " + node.getId());
        }
        List<Map<String, Object>> outputMappings = AgentGraphNodeConfigs.configMapList(node, AgentGraphInterpreter.CONFIG_KEY_SUBGRAPH_OUTPUTS);
        if (!outputMappings.isEmpty()) {
            for (Map<String, Object> mapping : outputMappings) {
                String name = AgentGraphNodeConfigs.stringValue(mapping.get("name"));
                String source = AgentGraphNodeConfigs.stringValue(mapping.get("source"));
                if (name.isEmpty() || source.isEmpty()) continue;
                String value = result.outputs.get(source);
                if (value != null) variables.put(name, value);
            }
        } else {
            result.outputs.forEach((key, value) -> {
                if (key != null && value != null) variables.put(key, value);
            });
        }
        try {
            trace.setInputText(AgentGraphTraceRecorder.JSON.writeValueAsString(subInputs));
            trace.setOutputText(AgentGraphTraceRecorder.JSON.writeValueAsString(result.outputs));
        } catch (JsonProcessingException ignored) {
            // 轨迹文本仅展示用途，序列化失败不阻断执行
        }
        trace.setChildExecutionId(result.childExecutionId);
    }
}
