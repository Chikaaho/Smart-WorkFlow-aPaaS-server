package com.sw.ck.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.system.api.dict.DictFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 表单字段校验共享工具。
 * <p>
 * 从 {@link FormSubmitService} 抽取，供提交、更新等数据写入路径复用同一套校验口径。
 * 校验逻辑统一收敛于此，避免多套口径导致的行为漂移。
 * </p>
 *
 * <h3>校验覆盖</h3>
 * <ul>
 *   <li>必填（1401）</li>
 *   <li>类型（NUMBER / DATE / BOOL，1402）</li>
 *   <li>字典值域（1403）</li>
 *   <li>未知字段（1400）</li>
 * </ul>
 */
@Component
public class FormFieldValidator {

    private static final Logger log = LoggerFactory.getLogger(FormFieldValidator.class);

    private final FormConfigMapper formConfigMapper;
    private final ObjectMapper objectMapper;

    public FormFieldValidator(FormConfigMapper formConfigMapper,
                              ObjectMapper objectMapper) {
        this.formConfigMapper = formConfigMapper;
        this.objectMapper = objectMapper;
    }

    // ==================== 字段定义加载 ====================

    /**
     * 从 sw_form_config.definition JSON 加载字段定义，并同步校验未知字段。
     *
     * @param formId        表单 ID
     * @param submittedData 提交/更新数据（用于检测未定义字段）
     * @return 字段名 → FieldDef 映射（保持 definition 中的顺序）
     */
    public Map<String, FieldDef> loadAndParseFieldDefs(String formId, Map<String, Object> submittedData) {
        // 从 sw_form_config 加载主表 definition JSON（parent_table IS NULL）
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormConfigEntity>()
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable)
        );
        FormConfigEntity config = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
        String definitionJson = (config != null) ? config.getDefinition() : null;

        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson)) {
            log.warn("Form config definition is empty for formId={}, skip field validation", formId);
            return new LinkedHashMap<>();
        }

        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray() || fieldsArray.isEmpty()) {
                log.warn("Form definition has no 'fields' array for formId={}, skip field validation", formId);
                return new LinkedHashMap<>();
            }

            Map<String, FieldDef> fieldDefs = new LinkedHashMap<>();
            for (JsonNode fieldNode : fieldsArray) {
                JsonNode nameNode = fieldNode.get("name");
                if (nameNode == null || nameNode.asText().isBlank()) continue;

                String name = nameNode.asText();
                String type = fieldNode.has("type") ? fieldNode.get("type").asText() : "TEXT";
                boolean required = fieldNode.has("required") && fieldNode.get("required").asBoolean();
                String dictType = fieldNode.has("dictType") ? fieldNode.get("dictType").asText() : null;
                Object defaultValue = fieldNode.has("defaultValue") && !fieldNode.get("defaultValue").isNull()
                        ? objectMapper.convertValue(fieldNode.get("defaultValue"), Object.class)
                        : null;

                // 解析 TABLE 子字段
                List<FieldDef> subFields = null;
                if ("TABLE".equals(type) && fieldNode.has("subFields")) {
                    JsonNode subArray = fieldNode.get("subFields");
                    if (subArray.isArray()) {
                        subFields = new ArrayList<>();
                        for (JsonNode sub : subArray) {
                            String subName = sub.has("name") ? sub.get("name").asText() : null;
                            if (subName == null) continue;
                            String subType = sub.has("type") ? sub.get("type").asText() : "TEXT";
                            boolean subRequired = sub.has("required") && sub.get("required").asBoolean();
                            String subDictType = sub.has("dictType") ? sub.get("dictType").asText() : null;
                            Object subDefault = sub.has("defaultValue") && !sub.get("defaultValue").isNull()
                                    ? objectMapper.convertValue(sub.get("defaultValue"), Object.class)
                                    : null;
                            String subLabel = sub.has("label") && !sub.get("label").asText().isBlank()
                                    ? sub.get("label").asText() : null;
                            subFields.add(new FieldDef(subName, subType, subRequired, subDictType, null,
                                    subDefault, subLabel));
                        }
                    }
                }

                String label = fieldNode.has("label") && !fieldNode.get("label").asText().isBlank()
                        ? fieldNode.get("label").asText() : null;
                fieldDefs.put(name, new FieldDef(name, type, required, dictType, subFields,
                        defaultValue, label));
            }

            // 检查未知字段
            for (String submittedField : submittedData.keySet()) {
                if (!fieldDefs.containsKey(submittedField)) {
                    throw new BaseException(FormErrorCode.SUBMIT_FIELD_UNKNOWN,
                            "提交了未定义的字段: '" + submittedField + "'");
                }
            }

            return fieldDefs;

        } catch (JsonProcessingException e) {
            log.error("Failed to parse form definition JSON for formId={}", formId, e);
            throw new BaseException(FormErrorCode.SUBMIT_DEFINITION_INVALID, "表单定义配置解析失败");
        }
    }

    // ==================== 字段校验 ====================

    /**
     * 校验字段值（必填、类型、字典值域）。
     * <p>
     * 全部校验通过才返回；任一失败抛 {@link BaseException}。
     * 与提交路径使用完全相同的校验口径（1400-1404 错误码）。
     * </p>
     *
     * @param fieldDefs     字段定义映射
     * @param submittedData 待校验数据
     * @param dictFacade    字典门面（用于字典值域校验）
     */
    public void validateFields(Map<String, FieldDef> fieldDefs,
                               Map<String, Object> submittedData,
                               DictFacade dictFacade) {
        if (fieldDefs.isEmpty()) {
            return; // 无定义时不校验
        }

        for (FieldDef def : fieldDefs.values()) {
            Object value = submittedData.get(def.name);

            // LABEL 非输入字段：无值、不参与必填与类型校验
            if ("LABEL".equals(def.type)) {
                continue;
            }

            // —— 必填校验 ——
            if (def.required) {
                boolean isEmpty = value == null
                        || (value instanceof String s && s.isBlank());
                if ("TABLE".equals(def.type)) {
                    // TABLE 类型检查是否为空列表
                    isEmpty = value == null
                            || (value instanceof List<?> list && list.isEmpty());
                }
                if (isEmpty) {
                    throw new BaseException(FormErrorCode.SUBMIT_FIELD_REQUIRED,
                            "必填字段「" + def.displayName() + "」缺失");
                }
            }

            // —— TABLE 子行校验：每行子字段必填/类型与主字段同口径 ——
            if ("TABLE".equals(def.type) && value instanceof List<?> rows) {
                for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                    Object rowObj = rows.get(rowIdx);
                    if (!(rowObj instanceof Map<?, ?> rowMap)) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」第 " + (rowIdx + 1) + " 行格式不正确");
                    }
                    for (FieldDef subDef : def.subFields) {
                        if (subDef == null) continue;
                        Object subValue = rowMap.get(subDef.name);
                        boolean subEmpty = subValue == null
                                || (subValue instanceof String sv && sv.isBlank());
                        if (subDef.required() && subEmpty) {
                            throw new BaseException(FormErrorCode.SUBMIT_FIELD_REQUIRED,
                                    "字段「" + def.displayName() + "」第 " + (rowIdx + 1)
                                            + " 行的「" + subDef.displayName() + "」为必填");
                        }
                        if (subEmpty) continue;
                        try {
                            validateSingleValue(subDef, subValue, dictFacade);
                        } catch (BaseException e) {
                            throw new BaseException(e.getCode(),
                                    "字段「" + def.displayName() + "」第 " + (rowIdx + 1) + " 行："
                                            + e.getMessage());
                        }
                    }
                }
                continue;
            }

            // 空值免后续校验
            if (value == null || (value instanceof String s && s.isBlank())) {
                continue;
            }

            validateSingleValue(def, value, dictFacade);
        }
    }

    /**
     * 单值校验（类型/字典值域；必填由调用方先行判断）。
     * 主字段与 TABLE 子行共用同一口径（1402/1403）。
     */
    private void validateSingleValue(FieldDef def, Object value, DictFacade dictFacade) {
            if (value == null || (value instanceof String s && s.isBlank())) {
                return;
            }
            switch (def.type) {
                case "NUMBER" -> {
                    if (!(value instanceof Number)) {
                        if (value instanceof String s) {
                            try {
                                new java.math.BigDecimal(s);
                            } catch (NumberFormatException e) {
                                throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                        "字段「" + def.displayName() + "」需要数字，当前填写的内容不是数字");
                            }
                        } else {
                            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                    "字段「" + def.displayName() + "」需要数字");
                        }
                    }
                }
                case "DATE" -> {
                    if (!(value instanceof String) && !(value instanceof Number)
                            && !(value instanceof java.time.temporal.Temporal)
                            && !(value instanceof java.util.Date)) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」需要日期");
                    }
                }
                case "BOOL" -> {
                    Object converted = convertBoolValue(value);
                    if (converted == null) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」需要选择是/否");
                    }
                }
                case "DICT" -> {
                    String dictType = def.dictType;
                    if (dictType == null || dictType.isBlank()) {
                        log.warn("DICT field '{}' has no dictType, skip dict validation", def.name);
                    } else {
                        String code = String.valueOf(value);
                        // dictType 与 code 均非空白 ⇒ 契约保证 present（empty 只表达缺少判定目标）
                        boolean valid = dictFacade.isValidCode(dictType, code).orElseThrow(() ->
                                new IllegalStateException(
                                        "字典值域判定缺少查询目标: dictType=" + dictType));
                        if (!valid) {
                            throw new BaseException(FormErrorCode.SUBMIT_DICT_INVALID,
                                    "字段「" + def.displayName() + "」的选项不在允许范围内，请重新选择");
                        }
                    }
                }
                case "MULTISELECT" -> {
                    if (!(value instanceof List<?> list)
                            || list.stream().anyMatch(v -> !(v instanceof String))) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」需要多选列表");
                    }
                }
                case "ATTACHMENT", "IMAGE" -> {
                    if (!(value instanceof List<?> list)) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」需要上传文件");
                    }
                }
                case "TIME" -> {
                    if (!(value instanceof String time) || !time.matches("^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$")) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」需要 HH:mm 或 HH:mm:ss 时间格式");
                    }
                }
                case "USER", "DEPT" -> {
                    // 数字型对象 ID；存在性/启用/租户校验由 FormFieldEnrichmentService 经 Facade 执行
                    if (!(value instanceof Number) && !(value instanceof String id && id.matches("\\d+"))) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」需要选择具体的人员或部门");
                    }
                }
                case "DATASOURCE" -> {
                    // 客户端只送稳定对象标识；服务端增补后允许规范化摘要继续通过二次校验。
                    boolean stableId = value instanceof Number || value instanceof String;
                    boolean serverSummary = value instanceof Map<?, ?> summary
                            && summary.get("value") != null
                            && summary.get("display") != null
                            && summary.get("queryKey") != null
                            && summary.get("version") != null;
                    if (!stableId && !serverSummary) {
                        throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                                "字段「" + def.displayName() + "」需要选择一条有效记录");
                    }
                }
                // TEXT / RICH_TEXT / REFERENCE / LABEL 无额外校验
            }
    }

    // ==================== BOOL 转换 ====================

    /**
     * BOOL 值转换：true/false/"true"/"false"/1/0 → 1/0（SMALLINT）。
     *
     * @return Integer 1 或 0；无法转换返回 null
     */
    public static Integer convertBoolValue(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.intValue() != 0 ? 1 : 0;
        if (value instanceof String s) {
            return switch (s.trim().toLowerCase()) {
                case "true", "1", "yes", "on", "是" -> 1;
                case "false", "0", "no", "off", "", "否" -> 0;
                default -> null;
            };
        }
        return null;
    }

    // ==================== 默认值应用 ====================

    /**
     * 应用静态默认值（v0.0.2）：仅当字段在载荷中不存在或值为空时应用；
     * 已有值（含草稿恢复/编辑数据）一律不覆盖。返回新映射，不改入参。
     */
    public Map<String, Object> applyDefaults(Map<String, FieldDef> fieldDefs, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (FieldDef def : fieldDefs.values()) {
            if (def.defaultValue() == null || "LABEL".equals(def.type)) {
                continue;
            }
            Object existing = result.get(def.name);
            boolean empty = existing == null || (existing instanceof String s && s.isBlank());
            if (empty) {
                result.put(def.name, def.defaultValue());
            }
        }
        return result;
    }

    /**
     * 加载表单主 definition JSON（parent_table IS NULL），供显隐规则解析等复用。
     */
    public String loadDefinitionJson(String formId) {
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormConfigEntity>()
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable)
        );
        FormConfigEntity config = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
        return (config != null) ? config.getDefinition() : null;
    }

    // ==================== 内部类型 ====================

    /**
     * 表单字段定义（从 definition JSON 解析）。
     */
    public record FieldDef(
            String name,
            String type,
            boolean required,
            String dictType,
            List<FieldDef> subFields,
            Object defaultValue,
            String label
    ) {
        /**
         * 用户可见的字段名：优先设计者填写的显示名，缺省回退字段键（不产生空名称）。
         * P61 阶段 C7：校验提示对用户展示显示名，不再暴露字段键。
         */
        public String displayName() {
            return label == null || label.isBlank() ? name : label;
        }
    }
}
