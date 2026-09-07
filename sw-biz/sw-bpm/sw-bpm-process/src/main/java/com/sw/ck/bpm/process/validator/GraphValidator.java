package com.sw.ck.bpm.process.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeDefinition;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程图画布校验器。
 * <p>
 * 仅解释图的拓扑结构（id/kind/type/source/target），不解析 config/style。
 * 类型判定、拓扑与配置能力均走 {@link BpmNodeRegistry} 的唯一注册结果，禁止平行类型表。
 * </p>
 */
@Component
public class GraphValidator {

    private static final Logger log = LoggerFactory.getLogger(GraphValidator.class);

    private static final String KIND_NODE = "node";
    private static final String KIND_EDGE = "edge";
    private static final Pattern FORM_FIELD = Pattern.compile(
            "(?:form|data)\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FORM_COMPARISON = Pattern.compile(
            "(?:form|data)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*(>=|<=|==|!=|>|<)");

    private final BpmNodeRegistry nodeRegistry;
    private final FormDefinitionService formDefinitionService;

    public GraphValidator(BpmNodeRegistry nodeRegistry, FormDefinitionService formDefinitionService) {
        this.nodeRegistry = nodeRegistry;
        this.formDefinitionService = formDefinitionService;
    }

    /**
     * 校验完整的 ProcessGraph。
     *
     * @param elements 图元素列表
     * @param formKey  绑定表单 formKey（可为 null，为 null 时跳过表单存在校验）
     * @return 校验错误列表，空列表表示通过
     */
    public List<GraphValidationError> validate(List<GraphElement> elements, String formKey) {
        List<GraphValidationError> errors = new ArrayList<>();

        if (elements == null || elements.isEmpty()) {
            errors.add(err(null, BpmErrorCode.GRAPH_MISSING_START));
            errors.add(err(null, BpmErrorCode.GRAPH_MISSING_END));
            return errors;
        }

        // 分离节点与边
        List<GraphElement> nodes = elements.stream()
                .filter(e -> KIND_NODE.equals(e.getKind()))
                .toList();
        List<GraphElement> edges = elements.stream()
                .filter(e -> KIND_EDGE.equals(e.getKind()))
                .toList();

        Set<String> nodeIds = nodes.stream()
                .map(GraphElement::getId)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);

        // --- 1. 恰好一个 START、一个 END ---
        List<GraphElement> starts = nodes.stream()
                .filter(this::isStartNode).toList();
        List<GraphElement> ends = nodes.stream()
                .filter(this::isEndNode).toList();

        if (starts.isEmpty()) {
            errors.add(err(null, BpmErrorCode.GRAPH_MISSING_START));
        } else if (starts.size() > 1) {
            for (GraphElement s : starts) {
                errors.add(err(s.getId(), BpmErrorCode.GRAPH_MULTIPLE_START));
            }
        }

        if (ends.isEmpty()) {
            errors.add(err(null, BpmErrorCode.GRAPH_MISSING_END));
        } else if (ends.size() > 1) {
            for (GraphElement e : ends) {
                errors.add(err(e.getId(), BpmErrorCode.GRAPH_MULTIPLE_END));
            }
        }

        // --- 2. 未知节点类型 ---
        for (GraphElement node : nodes) {
            String type = node.getType();
            if (type == null || nodeRegistry.find(type).isEmpty()) {
                errors.add(err(node.getId(), BpmErrorCode.GRAPH_UNKNOWN_NODE_TYPE));
            } else {
                errors.addAll(nodeRegistry.validateConfig(node));
            }
        }

        // --- 3. 边校验：source/target 存在、非自环、非重复 ---
        Set<String> edgeKeys = new HashSet<>();
        for (GraphElement edge : edges) {
            String source = edge.getSource();
            String target = edge.getTarget();

            // 指向不存在的节点
            if (source != null && !nodeIds.contains(source)) {
                errors.add(err(edge.getId(), BpmErrorCode.GRAPH_EDGE_TARGET_NOT_FOUND));
            }
            if (target != null && !nodeIds.contains(target)) {
                errors.add(err(edge.getId(), BpmErrorCode.GRAPH_EDGE_TARGET_NOT_FOUND));
            }

            // 自环
            if (source != null && source.equals(target)) {
                errors.add(err(edge.getId(), BpmErrorCode.GRAPH_ILLEGAL_EDGE));
            }

            // 重复边
            if (source != null && target != null) {
                String key = source + "->" + target;
                if (!edgeKeys.add(key)) {
                    errors.add(err(edge.getId(), BpmErrorCode.GRAPH_ILLEGAL_EDGE));
                }
            }
        }

        // --- 3.5 条件分支出口配置：恰好一个默认出口，其余出口必须是受控表达式 ---
        Map<String, String> formFieldTypes = formFieldTypes(formKey);
        for (GraphElement node : nodes) {
            if (!"CONDITION".equals(node.getType())) continue;
            // P57 的历史保留骨架是 CONDITION → EXCLUSIVE_GATEWAY；
            // CONDITION 本身不是运行时分支出口，保持该历史图可读可部署。
            List<GraphElement> outgoing = edges.stream()
                    .filter(edge -> node.getId() != null && node.getId().equals(edge.getSource()))
                    .toList();
            if (isLegacyGatewayPlaceholder(node, outgoing, nodes)) continue;
            long defaults = outgoing.stream().filter(this::isDefaultBranch).count();
            if (outgoing.size() < 2 || defaults != 1) {
                errors.add(err(node.getId(), BpmErrorCode.BRANCH_CONFIG_INVALID));
            }
            Set<Integer> priorities = new HashSet<>();
            for (GraphElement edge : outgoing) {
                if (isDefaultBranch(edge)) continue;
                String expression = branchExpression(edge);
                if (expression == null || expression.isBlank()
                        || expression.contains("eval") || expression.contains("new Function")
                        || expression.contains(";") || expression.contains("{")) {
                    errors.add(err(edge.getId(), BpmErrorCode.BRANCH_CONFIG_INVALID));
                    continue;
                }
                try {
                    RestrictedExpressionEvaluator.value(expression, Map.of());
                } catch (RuntimeException ex) {
                    errors.add(err(edge.getId(), BpmErrorCode.BRANCH_CONFIG_INVALID));
                }
                if (!validateFormReferences(expression, formFieldTypes)) {
                    errors.add(err(edge.getId(), BpmErrorCode.BRANCH_CONFIG_INVALID));
                }
                Object priority = edge.getConfig() == null ? null : edge.getConfig().get("priority");
                if (priority == null) {
                    errors.add(err(edge.getId(), BpmErrorCode.BRANCH_CONFIG_INVALID));
                } else {
                    try {
                        int value = Integer.parseInt(String.valueOf(priority));
                        if (value < 1 || !priorities.add(value)) {
                            errors.add(err(edge.getId(), BpmErrorCode.BRANCH_CONFIG_INVALID));
                        }
                    } catch (NumberFormatException ex) {
                        errors.add(err(edge.getId(), BpmErrorCode.BRANCH_CONFIG_INVALID));
                    }
                }
            }
        }

        // --- 4. 节点入/出度基数校验 ---
        // 仅在 START/END 数量正确 + 类型全注册时才做基数校验（否则基数无意义）
        if (starts.size() == 1 && ends.size() == 1 && errors.stream()
                .noneMatch(e -> e.getErrorCode() == BpmErrorCode.GRAPH_UNKNOWN_NODE_TYPE.getCode())) {

            Map<String, Integer> inDegree = new HashMap<>();
            Map<String, Integer> outDegree = new HashMap<>();
            for (String nid : nodeIds) {
                inDegree.put(nid, 0);
                outDegree.put(nid, 0);
            }

            for (GraphElement edge : edges) {
                if (edge.getSource() != null && nodeIds.contains(edge.getSource())) {
                    outDegree.merge(edge.getSource(), 1, Integer::sum);
                }
                if (edge.getTarget() != null && nodeIds.contains(edge.getTarget())) {
                    inDegree.merge(edge.getTarget(), 1, Integer::sum);
                }
            }

            for (GraphElement node : nodes) {
                String type = node.getType();
                if (type == null) continue;
                BpmNodeDefinition definition = nodeRegistry.find(type).orElse(null);
                if (definition == null || definition.metadata() == null) continue;
                if ("CONDITION".equals(type)) {
                    List<GraphElement> outgoing = edges.stream()
                            .filter(edge -> node.getId() != null && node.getId().equals(edge.getSource()))
                            .toList();
                    if (isLegacyGatewayPlaceholder(node, outgoing, nodes)) continue;
                }
                var topology = definition.metadata().topology();

                int in = inDegree.getOrDefault(node.getId(), 0);
                int out = outDegree.getOrDefault(node.getId(), 0);

                if (in < topology.minIncoming() || in > topology.maxIncoming()
                        || out < topology.minOutgoing() || out > topology.maxOutgoing()) {
                    errors.add(err(node.getId(), BpmErrorCode.GRAPH_NODE_EDGE_CARDINALITY));
                }
            }
        }

        // --- 5. 连通性（无孤儿） ---
        // 正向 BFS：从 START 出发可到达的所有节点
        // 反向 BFS：从 END 出发可反向到达的所有节点
        // 任一节点未被两者之一覆盖 → 孤儿
        if (starts.size() == 1 && ends.size() == 1) {
            // 构建邻接表
            Map<String, List<String>> forward = new HashMap<>();
            Map<String, List<String>> backward = new HashMap<>();
            for (String nid : nodeIds) {
                forward.put(nid, new ArrayList<>());
                backward.put(nid, new ArrayList<>());
            }
            for (GraphElement edge : edges) {
                String s = edge.getSource();
                String t = edge.getTarget();
                if (s != null && t != null && nodeIds.contains(s) && nodeIds.contains(t)) {
                    forward.get(s).add(t);
                    backward.get(t).add(s);
                }
            }

            String startId = starts.get(0).getId();
            String endId = ends.get(0).getId();

            Set<String> forwardReachable = bfs(forward, startId);
            Set<String> backwardReachable = bfs(backward, endId);

            for (String nid : nodeIds) {
                if (!forwardReachable.contains(nid) || !backwardReachable.contains(nid)) {
                    errors.add(err(nid, BpmErrorCode.GRAPH_ORPHAN_NODE));
                }
            }
        }

        // --- 6. 表单存在校验 ---
        if (formKey != null && !formKey.isBlank()) {
            if (!formDefinitionService.formExists(formKey)) {
                errors.add(err(null, BpmErrorCode.GRAPH_FORM_NOT_FOUND));
            }
        }

        return errors;
    }

    /** 发布期校验条件只引用已绑定表单字段，并拒绝把文本字段用于数值大小比较。 */
    private boolean validateFormReferences(String expression, Map<String, String> fieldTypes) {
        if (fieldTypes.isEmpty()) return true;
        Matcher fields = FORM_FIELD.matcher(expression);
        while (fields.find()) {
            if (!fieldTypes.containsKey(fields.group(1))) return false;
        }
        Matcher comparisons = FORM_COMPARISON.matcher(expression);
        while (comparisons.find()) {
            String operator = comparisons.group(2);
            if (">".equals(operator) || ">=".equals(operator)
                    || "<".equals(operator) || "<=".equals(operator)) {
                String type = fieldTypes.get(comparisons.group(1));
                if (type != null && !isNumericType(type)) return false;
            }
        }
        return true;
    }

    private boolean isNumericType(String type) {
        return Set.of("NUMBER", "INTEGER", "INT", "LONG", "DECIMAL", "FLOAT", "DOUBLE")
                .contains(type.toUpperCase(Locale.ROOT));
    }

    private Map<String, String> formFieldTypes(String formKey) {
        if (formKey == null || formKey.isBlank()) return Map.of();
        String definition;
        try {
            definition = formDefinitionService.getFormDefinition(formKey);
        } catch (RuntimeException e) {
            log.warn("读取表单字段用于分支校验失败: formKey={}, reason={}", formKey, e.getMessage());
            return Map.of();
        }
        if (definition == null || definition.isBlank()) return Map.of();
        try {
            JsonNode fields = new ObjectMapper().readTree(definition).path("fields");
            if (!fields.isArray()) return Map.of();
            Map<String, String> result = new HashMap<>();
            for (JsonNode field : fields) {
                String name = field.path("name").asText("");
                if (name.isBlank()) name = field.path("fieldName").asText("");
                if (!name.isBlank()) result.put(name, field.path("type").asText("TEXT"));
            }
            return result;
        } catch (Exception e) {
            log.warn("解析表单字段用于分支校验失败: formKey={}, reason={}", formKey, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 从 startNode 出发的 BFS 遍历。
     */
    private Set<String> bfs(Map<String, List<String>> adj, String startNode) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (adj.containsKey(startNode)) {
            queue.add(startNode);
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;
            for (String neighbor : adj.getOrDefault(current, Collections.emptyList())) {
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }

    private GraphValidationError err(String elementId, BpmErrorCode errorCode) {
        return GraphValidationError.builder()
                .elementId(elementId)
                .errorCode(errorCode.getCode())
                .message(errorCode.getMessage())
                .build();
    }

    private boolean isStartNode(GraphElement node) {
        return node.getType() != null && nodeRegistry.find(node.getType())
                .map(definition -> definition.metadata().startNode())
                .orElse(false);
    }

    private boolean isEndNode(GraphElement node) {
        return node.getType() != null && nodeRegistry.find(node.getType())
                .map(definition -> definition.metadata().endNode())
                .orElse(false);
    }

    private boolean isDefaultBranch(GraphElement edge) {
        return edge.getConfig() != null && (Boolean.TRUE.equals(edge.getConfig().get("default"))
                || Boolean.TRUE.equals(edge.getConfig().get("isDefault")));
    }

    private String branchExpression(GraphElement edge) {
        if (edge.getConfig() == null) return null;
        Object condition = edge.getConfig().get("condition");
        if (condition instanceof Map<?, ?> map && map.get("expression") != null) {
            return String.valueOf(map.get("expression"));
        }
        return edge.getConfig().get("expression") == null ? null
                : String.valueOf(edge.getConfig().get("expression"));
    }

    private boolean isLegacyGatewayPlaceholder(GraphElement node, List<GraphElement> outgoing,
                                               List<GraphElement> nodes) {
        if (outgoing.size() != 1) return false;
        String targetId = outgoing.get(0).getTarget();
        return nodes.stream().anyMatch(candidate -> targetId != null
                && targetId.equals(candidate.getId())
                && "EXCLUSIVE_GATEWAY".equals(candidate.getType()));
    }
}
