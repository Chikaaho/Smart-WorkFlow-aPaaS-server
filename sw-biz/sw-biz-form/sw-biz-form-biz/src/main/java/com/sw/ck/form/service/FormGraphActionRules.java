package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.form.entity.FormDefEntity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 表单 Graph 动作规则（1.2.0 P67 / E1-6）：从表单定义 JSON 根节点解析
 * {@code graphActions} 段——「调用 Graph」服务端动作声明。
 *
 * <p>动作结构：{@code {id, name, trigger: BEFORE_SUBMIT|BUTTON, graphDefId,
 * graphDefVersion, inputMappings:[{name:图入参, field:表单字段}],
 * outputMappings:[{field:表单字段, source:图出参}], timeoutSeconds}}。
 * 与显隐规则（FormVisibilityRules）同一解析形态：定义 JSON 附加段、宽松解析、
 * 未配置即空表。执行语义见 {@link FormGraphActionService}（服务端执行，
 * 不在前端脚本解释器内调用）。</p>
 */
@Service
public class FormGraphActionRules {

    public static final String TRIGGER_BEFORE_SUBMIT = "BEFORE_SUBMIT";
    public static final String TRIGGER_BUTTON = "BUTTON";

    /** 单个「调用 Graph」动作声明。 */
    public record GraphAction(
            String id,
            String name,
            String trigger,
            Long graphDefId,
            Integer graphDefVersion,
            List<InputMapping> inputMappings,
            List<OutputMapping> outputMappings,
            Integer timeoutSeconds) {
    }

    /** 入参映射：图开始节点参数名 ← 表单字段名。 */
    public record InputMapping(String name, String field) {
    }

    /** 出参映射：表单字段名 ← 图结束节点输出名。 */
    public record OutputMapping(String field, String source) {
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FormFieldValidator formFieldValidator;

    /** 解析定义 JSON 的 graphActions 段（未配置/解析失败 → 空表，不阻断提交主链）。 */
    public List<GraphAction> parse(FormDefEntity formDef) {
        if (formDef == null) {
            return List.of();
        }
        return parseJson(formFieldValidator.loadDefinitionJson(formDef.getId()));
    }

    public List<GraphAction> parseJson(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode actions = root == null ? null : root.get("graphActions");
            if (actions == null || !actions.isArray()) {
                return List.of();
            }
            List<GraphAction> parsed = new ArrayList<>();
            for (JsonNode action : actions) {
                if (action == null || !action.isObject()) {
                    continue;
                }
                String id = text(action, "id");
                Long graphDefId = longValue(action, "graphDefId");
                Integer version = intValue(action, "graphDefVersion");
                if (id == null || graphDefId == null || version == null) {
                    continue;
                }
                List<InputMapping> inputs = new ArrayList<>();
                for (JsonNode mapping : array(action.get("inputMappings"))) {
                    String name = text(mapping, "name");
                    String field = text(mapping, "field");
                    if (name != null && field != null) {
                        inputs.add(new InputMapping(name, field));
                    }
                }
                List<OutputMapping> outputs = new ArrayList<>();
                for (JsonNode mapping : array(action.get("outputMappings"))) {
                    String field = text(mapping, "field");
                    String source = text(mapping, "source");
                    if (field != null && source != null) {
                        outputs.add(new OutputMapping(field, source));
                    }
                }
                Integer timeout = intValue(action, "timeoutSeconds");
                parsed.add(new GraphAction(id, text(action, "name"),
                        text(action, "trigger"), graphDefId, version,
                        List.copyOf(inputs), List.copyOf(outputs), timeout));
            }
            return List.copyOf(parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Iterable<JsonNode> array(JsonNode node) {
        return node != null && node.isArray() ? node : List.<JsonNode>of();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.canConvertToLong()) {
            return null;
        }
        long parsed = value.asLong();
        return parsed > 0 ? parsed : null;
    }

    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.canConvertToInt()) {
            return null;
        }
        int parsed = value.asInt();
        return parsed > 0 ? parsed : null;
    }
}
