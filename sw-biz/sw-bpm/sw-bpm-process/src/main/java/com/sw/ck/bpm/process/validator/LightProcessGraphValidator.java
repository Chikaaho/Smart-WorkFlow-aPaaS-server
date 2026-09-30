package com.sw.ck.bpm.process.validator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * 生产轻流程图约束校验器（P62 分级执行 · 发布门）。
 * <p>
 * 判定口径：图中含 {@code TXN_ACTION} 节点即视为生产轻流程，施加方向固定的形态约束——
 * 仅允许 START/END/CONDITION/事务动作节点（禁混人工等待、并行、通知等）、无环、
 * 事务动作节点 ≤16。不含事务动作节点的图不受本约束（既有普通流程兼容不变）。
 * 拓扑基数、出口配置等通用规则仍由 {@link GraphValidator} 负责，本类只做形态约束。
 * </p>
 */
@Component
public class LightProcessGraphValidator {

    /** 生产轻流程允许的节点类型白名单（方向固定：START/END/CONDITION/事务动作）。 */
    private static final Set<String> ALLOWED_TYPES = Set.of("START", "END", "CONDITION", "TXN_ACTION");

    /** 生产轻流程事务动作节点上限（方向合同：最多 16 个）。 */
    public static final int MAX_ACTION_NODES = 16;

    private static final String TXN_ACTION = "TXN_ACTION";

    /**
     * 校验轻流程形态约束。
     *
     * @param elements 图元素列表（已通过通用图校验）
     * @return 校验错误列表，空列表表示通过
     */
    public List<GraphValidationError> validate(List<GraphElement> elements) {
        List<GraphValidationError> errors = new ArrayList<>();
        if (elements == null || elements.isEmpty()) {
            return errors;
        }

        List<GraphElement> nodes = elements.stream()
                .filter(e -> "node".equals(e.getKind()))
                .toList();
        boolean isLightProcess = nodes.stream()
                .anyMatch(n -> TXN_ACTION.equals(n.getType()));
        if (!isLightProcess) {
            return errors;
        }

        Set<String> nodeIds = new HashSet<>();
        for (GraphElement node : nodes) {
            nodeIds.add(node.getId());
        }

        // --- 1. 节点类型白名单 ---
        for (GraphElement node : nodes) {
            if (node.getType() == null || !ALLOWED_TYPES.contains(node.getType())) {
                errors.add(err(node.getId(), BpmErrorCode.LIGHT_PROCESS_NODE_NOT_ALLOWED));
            }
        }

        // --- 2. 事务动作节点数量上限 ---
        long actionCount = nodes.stream()
                .filter(n -> TXN_ACTION.equals(n.getType()))
                .count();
        if (actionCount > MAX_ACTION_NODES) {
            errors.add(err(null, BpmErrorCode.LIGHT_PROCESS_ACTION_LIMIT_EXCEEDED));
        }

        // --- 3. 无环（迭代 DFS 三色标记；仅沿两端合法的边走） ---
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String nodeId : nodeIds) {
            adjacency.put(nodeId, new ArrayList<>());
        }
        for (GraphElement edge : elements) {
            if (!"edge".equals(edge.getKind())) continue;
            String source = edge.getSource();
            String target = edge.getTarget();
            if (source != null && target != null
                    && nodeIds.contains(source) && nodeIds.contains(target)) {
                adjacency.get(source).add(target);
            }
        }
        errors.addAll(findCycleNodes(adjacency, nodeIds));

        return errors;
    }

    /**
     * 迭代式 DFS 环检测，返回位于环上的节点（WHITE 未访问 / GRAY 在栈 / BLACK 完成）。
     */
    private List<GraphValidationError> findCycleNodes(Map<String, List<String>> adjacency,
                                                      Set<String> nodeIds) {
        Set<String> white = new HashSet<>(nodeIds);
        Set<String> gray = new HashSet<>();
        Set<String> black = new HashSet<>();
        Set<String> onCycle = new LinkedHashSet<>();

        for (String start : nodeIds) {
            if (!white.contains(start)) continue;
            Deque<String> stack = new ArrayDeque<>();
            Deque<java.util.Iterator<String>> iterators = new ArrayDeque<>();
            white.remove(start);
            gray.add(start);
            stack.push(start);
            iterators.push(adjacency.getOrDefault(start, List.of()).iterator());

            while (!stack.isEmpty()) {
                java.util.Iterator<String> it = iterators.peek();
                if (it.hasNext()) {
                    String next = it.next();
                    if (gray.contains(next)) {
                        // next 在当前 DFS 栈中 → 栈内从 next 到栈顶构成环
                        onCycle.add(next);
                        for (String node : stack) {
                            onCycle.add(node);
                            if (node.equals(next)) break;
                        }
                    } else if (white.remove(next)) {
                        gray.add(next);
                        stack.push(next);
                        iterators.push(adjacency.getOrDefault(next, List.of()).iterator());
                    }
                    // black：已完成，跳过
                } else {
                    String finished = stack.pop();
                    iterators.pop();
                    gray.remove(finished);
                    black.add(finished);
                }
            }
        }

        List<GraphValidationError> errors = new ArrayList<>();
        for (String nodeId : onCycle) {
            errors.add(err(nodeId, BpmErrorCode.LIGHT_PROCESS_CYCLE));
        }
        return errors;
    }

    private GraphValidationError err(String elementId, BpmErrorCode errorCode) {
        boolean edge = elementId != null && elementId.startsWith("edge");
        return GraphValidationError.builder()
                .elementId(elementId)
                .edgeKey(edge ? elementId : null)
                .nodeKey(edge ? null : elementId)
                .errorCode(errorCode.getCode())
                .message(errorCode.getMessage())
                .build();
    }
}
