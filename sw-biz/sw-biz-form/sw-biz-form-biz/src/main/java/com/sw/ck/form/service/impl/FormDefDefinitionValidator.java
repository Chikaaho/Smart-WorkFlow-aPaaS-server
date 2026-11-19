package com.sw.ck.form.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.integration.ExternalCapability;
import com.sw.ck.common.integration.ExternalCapabilityFacade;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.FieldSpec;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.service.FormExtDataService;
import com.sw.ck.form.service.FormFieldEnrichmentService;
import com.sw.ck.form.service.FormulaEngine;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * definition 解析校验闸门（唯一闸门）：保存（saveConfig）与发布（publish/
 * publishNewVersion）共用的 definition JSON 解析与全栈校验——colSpan 布局、
 * 文本长度/正则约束、字段类型权威目录、类型特定约束（DICT/REFERENCE/FORMULA/
 * DATASOURCE/TABLE）、FORMULA 依赖（公式引擎）、事件形状、允许集成清单、
 * DATASOURCE 数据源绑定。
 */
@Service
@Log4j2
class FormDefDefinitionValidator {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FormulaEngine formulaEngine;
    @Autowired(required = false)
    private FormExtDataService extDataService;
    // ==== 表单受控集成：保存/发布时按当前租户启用能力复核允许清单（测试构造可缺省） ====
    @Autowired(required = false)
    private ExternalCapabilityFacade externalCapabilityFacade;

    /**
     * 字段键 → 设计者可读显示名（R3b）：显隐规则校验失败时向设计者展示 label 而非内部键。
     * 解析失败按空映射处理——此时字段校验本身会先行报错。
     */
    Map<String, String> collectFieldDisplay(String definitionJson) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson == null ? "{}" : definitionJson);
            JsonNode fields = root == null ? null : root.get("fields");
            if (fields == null && root != null && root.isArray()) {
                fields = root;
            }
            return FormFieldEnrichmentService.collectFieldLabels(fields);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 从 definition JSON 解析字段并执行全栈校验。
     * <p>
     * 期望格式：{@code {"fields": [...]}} 或顶层数组（兼容旧格式）。
     * definition 是唯一字段真源——不再接受外部 fieldSpecs 入参。
     * </p>
     *
     * <h3>校验项（任一不过抛对应 FormErrorCode）</h3>
     * <ol>
     *   <li>type 字面量 ∈ FieldType 且 enabled=true</li>
     *   <li>列名过 ColumnValidation（白名单正则/保留字/前缀/长度）</li>
     *   <li>同级重复列名拒绝</li>
     *   <li>DICT 必须带 dictType</li>
     *   <li>REFERENCE 必须带 targetFormId</li>
     *   <li>TABLE 必须带 subFields；subFields 内不得再含 TABLE（禁递归）</li>
     *   <li>subFields 内字段过全部上述校验</li>
     * </ol>
     *
     * @param definitionJson config.definition JSON
     * @return 校验通过的字段规格列表
     * @throws BaseException 校验失败
     */
    List<FieldSpec> parseAndValidateFieldsFromDefinition(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson.trim())) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "表单 definition 为空，不能发布");
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (root == null || root.isNull()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 不能为 null");
            }
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                // 兼容旧格式：顶层数组
                if (root.isArray()) {
                    fieldsArray = root;
                } else {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "definition 中缺少 fields 数组");
                }
            }
            if (fieldsArray.isEmpty()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition 的 fields 数组为空");
            }
            List<FieldSpec> fields = new ArrayList<>();
            for (JsonNode node : fieldsArray) {
                validateLayoutFieldNode(node);
                fields.add(parseFieldNodeFromDefinition(node, false));
            }
            return fields;
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "definition JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 从 definition JSON 的单字段节点解析为 FieldSpec（含全栈校验）。
     *
     * @param node        JSON 节点
     * @param isSubField  是否在 TABLE subFields 内（用于递归禁止检查）
     * @return FieldSpec
     * @throws BaseException 校验失败
     */
    private FieldSpec parseFieldNodeFromDefinition(JsonNode node, boolean isSubField) {
        validateLayoutFieldNode(node);

        // —— 1. name ——
        if (!node.has("name") || node.get("name").asText().isBlank()) {
            throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING, "字段缺少 name");
        }
        String name = node.get("name").asText();

        // —— 2. type ——
        if (!node.has("type") || node.get("type").asText().isBlank()) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN, "字段 '" + name + "' 缺少 type");
        }
        String typeStr = node.get("type").asText();
        FieldType fieldType;
        try {
            fieldType = FieldType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                    "字段「" + name + "」使用了当前不支持的字段类型，请更换类型后重试");
        }

        // —— 3. enabled 检查 ——
        if (!fieldType.isEnabled()) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_DISABLED,
                    "字段「" + name + "」使用了暂未开放的字段类型，请更换类型后重试");
        }

        // —— 4. 列名 + 白名单校验 (跳过 TABLE / LABEL：均不产生物理列) ——
        if (fieldType != FieldType.TABLE && fieldType != FieldType.LABEL) {
            String physicalName = ColumnValidation.physicalColumnName(name, fieldType);
            try {
                ColumnValidation.validateColumnName(physicalName);
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.INVALID_COLUMN_NAME,
                        "存在不合法的字段标识，请按提示修改后重试");
            }
        }

        // —— 5. 类型特定约束 ——
        validateDefaultValueType(name, fieldType, node);
        return switch (fieldType) {
            case TEXT -> FieldSpec.text(name);
            case RICH_TEXT -> FieldSpec.richText(name);
            case NUMBER -> FieldSpec.number(name);
            case DATE -> FieldSpec.date(name);
            case BOOL -> FieldSpec.bool(name);
            case DICT -> {
                // BF20261013-009：source=custom 走自定义候选键值域，允许 dictType 为空；
                // dict（缺省）仍必须带 dictType。multiple=true（下拉多选）列宽走 VARCHAR(1000)。
                boolean customSource = "custom".equals(node.path("source").asText(""));
                String dictType = node.path("dictType").asText("");
                if (!customSource && dictType.isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "DICT 字段 '" + name + "' 必须带 dictType");
                }
                yield FieldSpec.dict(name, dictType, node.path("multiple").asBoolean(false));
            }
            case REFERENCE -> {
                if (!node.has("targetFormId") || node.get("targetFormId").asText().isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "REFERENCE 字段 '" + name + "' 必须带 targetFormId");
                }
                yield FieldSpec.ref(name, node.get("targetFormId").asText());
            }
            case MULTISELECT -> FieldSpec.multiselect(name);
            case ATTACHMENT -> FieldSpec.attachment(name);
            case IMAGE -> FieldSpec.image(name);
            case LABEL -> FieldSpec.label(name);
            case TIME -> FieldSpec.time(name);
            case USER -> FieldSpec.user(name, node.path("multiple").asBoolean(false));
            case DEPT -> FieldSpec.dept(name, node.path("multiple").asBoolean(false));
            case FORMULA -> {
                if (!node.has("expression") || node.get("expression").asText().isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "FORMULA 字段 '" + name + "' 必须带 expression");
                }
                yield FieldSpec.formula(name);
            }
            case DATASOURCE -> {
                JsonNode binding = node.get("dsBinding");
                if (binding == null || !binding.isObject()
                        || binding.path("queryKey").asText("").isBlank()
                        || binding.path("valueField").asText("").isBlank()
                        || binding.path("displayField").asText("").isBlank()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "DATASOURCE 字段 '" + name + "' 必须带 dsBinding{queryKey,version,valueField,displayField}");
                }
                JsonNode version = binding.get("version");
                if (version != null && !version.canConvertToInt()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "DATASOURCE 字段 '" + name + "' 的 dsBinding.version 必须是整数");
                }
                yield FieldSpec.datasource(name);
            }
            case TABLE -> {
                // —— 递归禁止（C: TABLE 套 TABLE 硬拦截） ——
                if (isSubField) {
                    throw new BaseException(FormErrorCode.FIELD_NESTED_TABLE,
                            "TABLE 字段 '" + name + "' 不能嵌套在另一个 TABLE 的 subFields 中（禁止递归）");
                }
                if (!node.has("subFields") || !node.get("subFields").isArray()
                        || node.get("subFields").isEmpty()) {
                    throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                            "TABLE 字段 '" + name + "' 必须带 subFields 且非空");
                }
                List<FieldSpec> subFields = new ArrayList<>();
                for (JsonNode subNode : node.get("subFields")) {
                    // 子字段必过全部校验 isSubField=true
                    subFields.add(parseFieldNodeFromDefinition(subNode, true));
                }
                yield FieldSpec.table(name, subFields);
            }
            // disabled 占位成员 — 已在 enabled 检查中拦截，不会到达此处
            case EMAIL, PHONE, URL, RATE, SLIDER ->
                    throw new BaseException(FormErrorCode.FIELD_TYPE_DISABLED,
                            "该字段类型暂未开放，请更换类型后重试");
        };
    }

    /**
     * 校验静态默认值与字段类型匹配（v0.0.2）。
     * <p>
     * 仅做类型匹配静态校验：TEXT/RICH_TEXT/DICT/DATE → 文本；NUMBER → 数值；
     * BOOL → 布尔；MULTISELECT → 字符串数组；ATTACHMENT/IMAGE → 数组；
     * LABEL/LINK 类非输入字段不允许默认值。类型不符按 1206 ATTR_MISSING 语义拒绝发布。
     * </p>
     */
    private void validateDefaultValueType(String name, FieldType fieldType, JsonNode node) {
        JsonNode defaultValue = node.get("defaultValue");
        if (defaultValue == null || defaultValue.isNull()) {
            return;
        }
        boolean valid = switch (fieldType) {
            case TEXT, RICH_TEXT, DICT, DATE, TIME -> defaultValue.isTextual() || defaultValue.isNumber();
            // USER/DEPT 单选默认=单个 ID；多选默认=ID 数组（P63）
            case USER, DEPT -> node.path("multiple").asBoolean(false)
                    ? defaultValue.isArray()
                    : defaultValue.isTextual() || defaultValue.isNumber();
            case NUMBER -> defaultValue.isNumber();
            case BOOL -> defaultValue.isBoolean();
            case MULTISELECT, ATTACHMENT, IMAGE -> defaultValue.isArray();
            case LABEL, REFERENCE, TABLE, FORMULA, DATASOURCE -> false;
            default -> defaultValue.isTextual();
        };
        if (!valid) {
            throw new BaseException(FormErrorCode.FIELD_ATTR_MISSING,
                    "字段 '" + name + "' 的默认值与类型 " + fieldType + " 不匹配");
        }
    }

    /**
     * 校验并拒绝 definition 中的非法 24 列布局值。
     * <p>
     * colSpan 缺省表示旧 definition，交给前端按字段类型补默认值；一旦携带就必须是
     * 1—24 的整数。校验递归覆盖 TABLE 的 subFields，避免不同入口各自解释值域。
     * </p>
     */
    void validateLayoutDefinition(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson.trim())) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (root == null || root.isNull()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 不能为 null");
            }
            JsonNode fieldsArray = root.isArray() ? root : root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return;
            }
            for (JsonNode fieldNode : fieldsArray) {
                validateLayoutFieldNode(fieldNode);
            }
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "definition JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 字段类型权威校验（Z8/G14a）：config 保存层拒绝未知与未启用类型。
     * 未知类型用 FIELD_TYPE_UNKNOWN(1204)，未启用类型用 FIELD_TYPE_DISABLED(1205)，
     * 与 publish 的 validFieldTypes 校验同口径（ColumnValidation 权威目录）。
     */
    void validateFieldTypes(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson.trim())) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(definitionJson);
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "definition JSON 解析失败: " + e.getMessage());
        }
        JsonNode fieldsArray = (root == null || root.isNull()) ? null
                : (root.isArray() ? root : root.get("fields"));
        if (fieldsArray == null || !fieldsArray.isArray()) {
            return;
        }
        for (JsonNode fieldNode : fieldsArray) {
            String type = fieldNode.path("type").asText("");
            if (type.isBlank()) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                        "字段缺少 type: " + fieldNode.path("name").asText(""));
            }
            FieldType fieldType;
            try {
                fieldType = FieldType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                        "字段类型未知: " + type);
            }
            if (!fieldType.isEnabled()) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_DISABLED,
                        "字段类型暂不允许发布: " + type);
            }
        }
    }

    private void validateLayoutFieldNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition 字段必须是对象");
        }

        JsonNode colSpan = node.get("colSpan");
        if (colSpan != null && (!colSpan.isIntegralNumber()
                || colSpan.intValue() < 1 || colSpan.intValue() > 24)) {
            String name = node.path("name").asText("<unknown>");
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "字段 '" + name + "' 的 colSpan 必须是 1—24 的整数");
        }

        validateTextConstraints(node);

        JsonNode subFields = node.get("subFields");
        if (subFields != null && subFields.isArray()) {
            for (JsonNode subField : subFields) {
                validateLayoutFieldNode(subField);
            }
        }
    }

    private void validateTextConstraints(JsonNode node) {
        String name = node.path("name").asText("<unknown>");
        String type = node.path("type").asText("");
        JsonNode min = node.get("minLength");
        JsonNode max = node.get("length");
        JsonNode pattern = node.get("pattern");
        JsonNode message = node.get("patternMessage");
        boolean configured = min != null || pattern != null || message != null;
        if (configured && !("TEXT".equals(type) || "RICH_TEXT".equals(type))) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "字段「" + name + "」的长度或正则约束仅适用于文本字段");
        }
        int minValue = 0;
        if (min != null && !min.isNull()) {
            if (!min.isIntegralNumber() || !min.canConvertToInt()
                    || min.asInt() < 0 || min.asInt() > 1_000_000) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                        "字段「" + name + "」的最小长度必须为 0—1000000 的整数");
            }
            minValue = min.asInt();
        }
        if (max != null && !max.isNull()) {
            if (!max.isIntegralNumber() || !max.canConvertToInt()
                    || max.asInt() < 0 || max.asInt() > 1_000_000) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                        "字段「" + name + "」的最大长度必须为 0—1000000 的整数");
            }
            if (max.asInt() > 0 && minValue > max.asInt()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                        "字段「" + name + "」的最小长度不能大于最大长度");
            }
        }
        if (message != null && !message.isNull()) {
            if (!message.isTextual() || message.asText().length() > 200) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                        "字段「" + name + "」的校验提示必须是不超过 200 个字符的文本");
            }
        }
        if (pattern == null || pattern.isNull() || pattern.asText().isBlank()) {
            return;
        }
        if (!pattern.isTextual() || pattern.asText().length() > 512) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "字段「" + name + "」的正则必须是不超过 512 个字符的文本");
        }
        try {
            Pattern.compile(pattern.asText());
        } catch (PatternSyntaxException invalid) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "字段「" + name + "」的正则语法无效，请检查后重试");
        }
    }

    /**
     * I2 发布校验：FORMULA 依赖（未知字段/循环）与 fieldPermissions 结构。
     */
    void validateFormulaDependencies(String definitionJson, Set<String> fieldNames) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.isArray() ? root : root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return;
            }
            Map<String, String> expressions = new LinkedHashMap<>();
            for (JsonNode field : fieldsArray) {
                if (FieldType.FORMULA.name().equals(field.path("type").asText())) {
                    expressions.put(field.path("name").asText(), field.path("expression").asText());
                }
            }
            if (!expressions.isEmpty()) {
                formulaEngine.validateDependencies(expressions, fieldNames);
            }
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 解析失败");
        }
    }

    /** 保存和发布共用的事件形状闸门；历史定义缺少 events 时保持原义。 */
    void validateFormEvents(String definitionJson) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode events = root == null ? null : root.get("events");
            if (events == null || events.isNull()) {
                return;
            }
            if (!events.isObject()) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID, "表单 events 必须是对象");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = events.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> event = fields.next();
                if (!Set.of("onLoad", "beforeSubmitHandler", "afterSubmitHandler").contains(event.getKey())) {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "不支持的表单事件: " + event.getKey());
                }
                if (!event.getValue().isArray() || event.getValue().size() > 50) {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "表单事件动作必须是数组且不超过 50 项: " + event.getKey());
                }
                Set<String> ids = new HashSet<>();
                for (JsonNode action : event.getValue()) {
                    String id = action.path("id").asText("").trim();
                    String script = action.path("script").asText("");
                    if (id.isEmpty() || id.length() > 128 || !ids.add(id)) {
                        throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                                "表单事件动作 ID 为空、重复或过长: " + event.getKey());
                    }
                    if (!action.path("order").isNumber() || script.length() > 100_000) {
                        throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                                "表单事件动作缺少有效顺序或脚本过长: " + id);
                    }
                    if (action.has("confirmMessage")
                            && action.get("confirmMessage").asText("").length() > 500) {
                        throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                                "表单事件确认说明过长: " + id);
                    }
                }
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 解析失败");
        }
    }

    /**
     * 表单允许集成清单闸门：结构合法、无重复，且（可取得当前租户时）每个标识都对应
     * 本租户启用且已分类的能力。运行时仍按版本快照与当前能力状态复核，历史快照不恢复已撤销权力。
     */
    void validateFormIntegrations(String definitionJson) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode allowed = root == null ? null : root.get("allowedIntegrations");
            if (allowed == null || allowed.isNull()) {
                return;
            }
            if (!allowed.isArray() || allowed.size() > 50) {
                throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                        "表单允许集成清单必须是数组且不超过 50 项");
            }
            Set<String> seen = new HashSet<>();
            LoginUser loginUser = LoginUserHolder.get();
            Long tenantId = loginUser == null ? null : loginUser.getTenantId();
            List<ExternalCapability> enabled =
                    (externalCapabilityFacade != null && tenantId != null)
                            ? externalCapabilityFacade.listEnabled(tenantId)
                            : null;
            for (JsonNode node : allowed) {
                if (node == null || !node.isTextual()) {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "表单允许集成清单包含非文本项");
                }
                String capabilityId = node.asText("").trim();
                if (!capabilityId.matches("^[A-Za-z_][A-Za-z0-9_]{0,63}$")) {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "集成能力标识不合法: " + capabilityId);
                }
                if (!seen.add(capabilityId)) {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "集成能力标识重复: " + capabilityId);
                }
                if (enabled != null && enabled.stream()
                        .noneMatch(capability -> capabilityId.equals(capability.name()))) {
                    throw new BaseException(FormErrorCode.INTEGRATION_UNAVAILABLE,
                            "集成能力未登记、跨租户、已停用或未分类: " + capabilityId);
                }
            }
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 解析失败");
        }
    }

    /**
     * I2 发布校验：DATASOURCE 字段的 dsBinding 必须命中启用中的查询契约。
     */
    void validateDatasourceBindings(String definitionJson) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.isArray() ? root : root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return;
            }
            boolean hasBinding = false;
            for (JsonNode field : fieldsArray) {
                JsonNode binding = field.path("dsBinding");
                if (!binding.isObject()) {
                    continue;
                }
                hasBinding = true;
                if (extDataService == null) {
                    throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND,
                            "外部数据源服务未装配，无法发布 DATASOURCE 字段");
                }
                extDataService.validateBinding(
                        binding.path("queryKey").asText(),
                        binding.hasNonNull("version") ? binding.get("version").intValue() : null,
                        binding.path("valueField").asText(),
                        binding.path("displayField").asText());
            }
            if (hasBinding) {
                log.info("Datasource bindings validated at publish");
            }
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "definition JSON 解析失败");
        }
    }
}
