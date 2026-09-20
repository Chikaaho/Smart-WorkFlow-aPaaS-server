package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表单字段显隐联动规则（v0.0.2 OA）。
 * <p>
 * definition 顶层 {@code rules.visibility} 形状：
 * <pre>{@code
 * "rules": {
 *   "visibility": [
 *     {"target": "fieldB", "logic": "ALL",
 *      "conditions": [{"field": "fieldA", "op": "EQ", "value": "x"}]}
 *   ]
 * }
 * }</pre>
 * 语义：target 字段仅在规则条件满足时可见（每字段至多一条规则）。
 * op ∈ EQ/NE/EMPTY/NOT_EMPTY；logic ∈ ALL/ANY。限制为无循环依赖（target 之间
 * 经 condition field 构成的依赖图必须无环），不执行任意脚本。
 * 服务端在正式提交时按载荷复算可见性并过滤隐藏字段（草稿保留原输入）。
 * </p>
 */
@Component
public class FormVisibilityRules {

    private static final Logger log = LoggerFactory.getLogger(FormVisibilityRules.class);

    private static final Set<String> OPS = Set.of("EQ", "NE", "EMPTY", "NOT_EMPTY");
    private static final Set<String> LOGICS = Set.of("ALL", "ANY");

    private final ObjectMapper objectMapper;

    public FormVisibilityRules(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 模型 ====================

    /** 单条显隐规则。 */
    public record VisibilityRule(String target, String logic, List<Condition> conditions) {
    }

    /** 规则条件。 */
    public record Condition(String field, String op, String value) {
    }

    // ==================== 解析与校验 ====================

    /**
     * 解析并校验 definition 的显隐规则（发布门调用）。
     *
     * @param fieldDisplay 字段键 → 设计者可读显示名（definition 的 label，缺省回退键）
     * @throws BaseException DEFINITION_INVALID：形状/字段/op/logic/环依赖不合法
     */
    public List<VisibilityRule> parseAndValidate(String definitionJson, Map<String, String> fieldDisplay) {
        List<VisibilityRule> rules = parse(definitionJson);
        if (rules.isEmpty()) {
            return rules;
        }
        Set<String> fieldNames = fieldDisplay.keySet();
        Set<String> targets = new HashSet<>();
        for (VisibilityRule rule : rules) {
            if (rule.target() == null || rule.target().isBlank()) {
                throw invalid("显隐规则缺少 target");
            }
            if (!fieldNames.contains(rule.target())) {
                // 目标字段未定义：只有设计者自己输入的键可回显，不泄露其他字段存在性
                throw invalid("显隐规则目标字段 '" + rule.target() + "' 不是已定义字段");
            }
            if (!targets.add(rule.target())) {
                throw invalid("字段「" + display(fieldDisplay, rule.target()) + "」存在多条显隐规则（每字段至多一条）");
            }
            if (rule.logic() == null || !LOGICS.contains(rule.logic())) {
                throw invalid("显隐规则 logic 必须为 ALL/ANY: " + display(fieldDisplay, rule.target()));
            }
            if (rule.conditions() == null || rule.conditions().isEmpty()) {
                throw invalid("显隐规则字段「" + display(fieldDisplay, rule.target()) + "」缺少条件");
            }
            for (Condition condition : rule.conditions()) {
                if (condition.field() == null || !fieldNames.contains(condition.field())) {
                    throw invalid("显隐规则条件字段 '" + condition.field() + "' 不是已定义字段");
                }
                if (condition.op() == null || !OPS.contains(condition.op())) {
                    throw invalid("字段「" + display(fieldDisplay, condition.field())
                            + "」的显隐规则 op 必须为 EQ/NE/EMPTY/NOT_EMPTY");
                }
                if (("EQ".equals(condition.op()) || "NE".equals(condition.op()))
                        && condition.value() == null) {
                    throw invalid("字段「" + display(fieldDisplay, condition.field())
                            + "」的显隐规则 op=" + condition.op() + " 必须携带 value");
                }
            }
        }
        assertAcyclic(rules, fieldDisplay);
        return rules;
    }

    /** 兼容旧签名：无显示名映射时回退为「键即显示名」。 */
    public List<VisibilityRule> parseAndValidate(String definitionJson, Set<String> fieldNames) {
        Map<String, String> fieldDisplay = new java.util.LinkedHashMap<>();
        for (String name : fieldNames) {
            fieldDisplay.put(name, name);
        }
        return parseAndValidate(definitionJson, fieldDisplay);
    }

    private static String display(Map<String, String> fieldDisplay, String key) {
        String name = fieldDisplay.get(key);
        return (name == null || name.isBlank()) ? key : name;
    }

    /** 解析规则（不校验），空/缺省返回空列表。 */
    public List<VisibilityRule> parse(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson.trim())) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode visibility = root == null ? null : root.get("rules");
            visibility = visibility == null ? null : visibility.get("visibility");
            if (visibility == null || !visibility.isArray() || visibility.isEmpty()) {
                return List.of();
            }
            List<VisibilityRule> rules = new ArrayList<>();
            for (JsonNode ruleNode : visibility) {
                String target = textOrNull(ruleNode.get("target"));
                String logic = textOrNull(ruleNode.get("logic"));
                List<Condition> conditions = new ArrayList<>();
                JsonNode conditionNodes = ruleNode.get("conditions");
                if (conditionNodes != null && conditionNodes.isArray()) {
                    for (JsonNode c : conditionNodes) {
                        conditions.add(new Condition(textOrNull(c.get("field")),
                                textOrNull(c.get("op")),
                                c.has("value") && !c.get("value").isNull()
                                        ? c.get("value").asText() : null));
                    }
                }
                rules.add(new VisibilityRule(target, logic, conditions));
            }
            return rules;
        } catch (Exception e) {
            log.warn("显隐规则解析失败，按无规则处理: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 求值 ====================

    /**
     * 计算当前载荷下应被隐藏的字段集合（服务端复算口径）。
     *
     * @param rules 已解析规则
     * @param data  提交载荷（已应用默认值前后的值均可；以当前值为准）
     */
    public Set<String> hiddenFields(List<VisibilityRule> rules, Map<String, Object> data) {
        if (rules.isEmpty()) {
            return Set.of();
        }
        Set<String> hidden = new HashSet<>();
        for (VisibilityRule rule : rules) {
            boolean visible = evaluate(rule, data);
            if (!visible) {
                hidden.add(rule.target());
            }
        }
        return hidden;
    }

    /** 单规则求值：条件按 logic 组合；条件字段缺失视为 EMPTY。 */
    boolean evaluate(VisibilityRule rule, Map<String, Object> data) {
        boolean isAll = "ALL".equals(rule.logic());
        boolean result = isAll;
        for (Condition condition : rule.conditions()) {
            boolean matched = match(condition, data.get(condition.field()));
            if (isAll) {
                result = result && matched;
                if (!result) {
                    return false;
                }
            } else {
                result = result || matched;
                if (result) {
                    return true;
                }
            }
        }
        return result;
    }

    private boolean match(Condition condition, Object rawValue) {
        boolean empty = isEmptyValue(rawValue);
        String op = condition.op();
        // 多选字段（List 值）的 EQ/NE 采用包含语义：选中项包含/不包含给定值
        if (rawValue instanceof java.util.Collection<?> collection) {
            boolean contains = condition.value() != null
                    && collection.stream().anyMatch(v -> String.valueOf(v).equals(condition.value()));
            return switch (op) {
                case "EQ" -> contains;
                case "NE" -> !contains;
                default -> false;
            };
        }
        return switch (op) {
            case "EMPTY" -> empty;
            case "NOT_EMPTY" -> !empty;
            case "EQ" -> !empty && condition.value() != null
                    && String.valueOf(rawValue).equals(condition.value());
            case "NE" -> empty || condition.value() == null
                    || !String.valueOf(rawValue).equals(condition.value());
            default -> false;
        };
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        if (value instanceof java.util.Collection<?> c) {
            return c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
    }

    // ==================== 内部方法 ====================

    /**
     * 环依赖检查：target → 条件字段（若条件字段本身也是某规则 target 则构成依赖边），
     * 依赖图必须无环（DFS 三色标记）。
     */
    private void assertAcyclic(List<VisibilityRule> rules, Map<String, String> fieldDisplay) {
        Map<String, VisibilityRule> byTarget = new LinkedHashMap<>();
        for (VisibilityRule rule : rules) {
            byTarget.put(rule.target(), rule);
        }
        Map<String, Integer> state = new HashMap<>();
        for (String target : byTarget.keySet()) {
            dfs(target, byTarget, state, fieldDisplay);
        }
    }

    private void dfs(String target, Map<String, VisibilityRule> byTarget, Map<String, Integer> state,
            Map<String, String> fieldDisplay) {
        Integer current = state.putIfAbsent(target, 1);
        if (current != null) {
            if (current == 1) {
                throw invalid("显隐规则存在循环依赖: " + display(fieldDisplay, target));
            }
            return;
        }
        VisibilityRule rule = byTarget.get(target);
        if (rule != null) {
            for (Condition condition : rule.conditions()) {
                if (byTarget.containsKey(condition.field())) {
                    dfs(condition.field(), byTarget, state, fieldDisplay);
                }
            }
        }
        state.put(target, 2);
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 显隐规则校验失败：把已带字段显示名的原因作为目录参数 {0} 传出，
     * 避免无参的 definition_invalid 目录条目覆盖掉「是哪个字段出的问题」。
     */
    private BaseException invalid(String message) {
        return new BaseException(FormErrorCode.DEFINITION_INVALID, new Object[]{message}, message);
    }
}
