package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.security.holder.LoginUser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 表单数据查询的共享底座：物理表名防御校验、sw_form_config.definition 元数据
 * 解析（字段类型映射 / TABLE 子字段 / 子表映射 / 字段显示名）、I2 字段查看权限
 * （无 view 权字段集合，含 TABLE 子字段键展开）与数据范围解析（缺省依赖时按
 * 登录态降级）。供列表查询、工作台组件、记录详情各协作类共用，本类不执行
 * 任何业务数据 SQL。
 */
@Service
@Log4j2
class FormDataQuerySupport {

    @Autowired
    private FormConfigMapper formConfigMapper;
    @Autowired
    private ObjectMapper objectMapper;

    // ==== I2 依赖（可选注入；缺省时按登录态降级解析） ====

    @Autowired(required = false)
    private FieldPermissionService fieldPermissionService;

    @Autowired(required = false)
    private LoginContextProvider loginContextProvider;

    @Autowired(required = false)
    private DeptScopeProvider deptScopeProvider;

    /**
     * 防御性表名校验（表名来自注册表，发布期已校验，此处为纵深防御）。
     * <p>与 {@link DynamicTableSql} 共用同一正则常量；受控入口在执行前会再校验一次。</p>
     */
    void validateTableName(String tableName) {
        if (!DynamicTableSql.isValidTableName(tableName)) {
            log.error("Table name '{}' does not match expected pattern '{}'",
                    tableName, DynamicTableSql.TABLE_NAME_PATTERN);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "该表单的数据表配置异常，请联系管理员处理");
        }
    }

    /**
     * 从 sw_form_config.definition JSON 解析字段名 → FieldType 映射。
     */
    Map<String, FieldType> loadFieldTypeMap(String formId) {
        // 查主表 definition（parent_table IS NULL）
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new LambdaQueryWrapper<FormConfigEntity>()
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable)
        );
        FormConfigEntity config = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
        String definitionJson = (config != null) ? config.getDefinition() : null;

        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson)) {
            log.warn("Form definition is empty for formId={}", formId);
            return Map.of();
        }

        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return Map.of();
            }

            Map<String, FieldType> map = new LinkedHashMap<>();
            for (JsonNode fieldNode : fieldsArray) {
                JsonNode nameNode = fieldNode.get("name");
                if (nameNode == null || nameNode.asText().isBlank()) continue;

                String name = nameNode.asText();
                String typeStr = fieldNode.has("type") ? fieldNode.get("type").asText() : FieldType.TEXT.name();

                FieldType fieldType;
                try {
                    fieldType = FieldType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown field type '{}' for field '{}', skipping", typeStr, name);
                    continue;
                }

                map.put(name, fieldType);
            }
            return map;

        } catch (JsonProcessingException e) {
            log.error("Failed to parse definition JSON for formId={}", formId, e);
            return Map.of();
        }
    }

    String loadDefinitionJsonForPermissions(String formId) {
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new LambdaQueryWrapper<FormConfigEntity>()
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable)
        );
        FormConfigEntity config = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
        return config != null ? config.getDefinition() : null;
    }

    /** 字段键 → 显示名（R3a）：筛选/操作符拒绝消息向用户展示 label 而非内部键。 */
    Map<String, String> fieldDisplayFor(String formId) {
        try {
            JsonNode root = objectMapper.readTree(loadDefinitionJsonForPermissions(formId));
            JsonNode fields = root == null ? null : root.get("fields");
            return FormFieldEnrichmentService.collectFieldLabels(fields);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * I2：解析当前身份在指定表单上的无 view 权字段集合
     * （含 TABLE 子字段，键形如 {@code items.qty}；权限服务缺失时视为不设限）。
     */
    Set<String> viewDeniedFields(LoginUser loginUser, String formId) {
        if (fieldPermissionService == null) {
            return Set.of();
        }
        String definitionJson = loadDefinitionJsonForPermissions(formId);
        if (definitionJson == null) {
            return Set.of();
        }
        Set<String> denied = new LinkedHashSet<>(fieldPermissionService.viewDeniedFields(loginUser,
                fieldPermissionService.parse(definitionJson)));
        // 子字段键展开：子字段权限键 = TABLE字段名.子字段名
        try {
            var perms = fieldPermissionService.parse(definitionJson);
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray != null && fieldsArray.isArray()) {
                for (JsonNode field : fieldsArray) {
                    if (!FieldType.TABLE.name().equals(field.path("type").asText())) continue;
                    String tableName = field.path("name").asText();
                    for (JsonNode sub : field.path("subFields")) {
                        String key = tableName + "." + sub.path("name").asText();
                        if (perms.containsKey(key) && !fieldPermissionService.canView(loginUser, key, perms)) {
                            denied.add(key);
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("viewDenied subfield expansion failed: {}", e.getMessage());
        }
        return denied;
    }

    /**
     * 加载 TABLE 字段的子字段元数据（字段名 → 类型）。
     */
    Map<String, List<SubFieldMeta>> loadTableSubFields(String formId) {
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new LambdaQueryWrapper<FormConfigEntity>()
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable)
        );
        FormConfigEntity config = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
        String definitionJson = (config != null) ? config.getDefinition() : null;

        if (definitionJson == null || definitionJson.isBlank() || "{}".equals(definitionJson)) {
            return Map.of();
        }

        Map<String, List<SubFieldMeta>> result = new LinkedHashMap<>();

        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray == null || !fieldsArray.isArray()) {
                return Map.of();
            }

            for (JsonNode fieldNode : fieldsArray) {
                String type = fieldNode.has("type") ? fieldNode.get("type").asText() : FieldType.TEXT.name();
                if (!"TABLE".equals(type)) continue;

                String name = fieldNode.has("name") ? fieldNode.get("name").asText() : null;
                if (name == null) continue;

                JsonNode subFieldsNode = fieldNode.get("subFields");
                if (subFieldsNode == null || !subFieldsNode.isArray()) continue;

                List<SubFieldMeta> subFields = new ArrayList<>();
                for (JsonNode subNode : subFieldsNode) {
                    String subName = subNode.has("name") ? subNode.get("name").asText() : null;
                    if (subName == null) continue;
                    String subType = subNode.has("type") ? subNode.get("type").asText() : FieldType.TEXT.name();
                    FieldType subFieldType;
                    try {
                        subFieldType = FieldType.valueOf(subType);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    if (!subFieldType.isEnabled() || subFieldType == FieldType.TABLE
                            || subFieldType == FieldType.LABEL) continue;

                    String physicalCol = DynamicTableSql.requireColumn(subName, subFieldType);
                    subFields.add(new SubFieldMeta(subName, subFieldType, physicalCol));
                }
                result.put(name, subFields);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse sub-field definitions for formId={}: {}", formId, e.getMessage());
        }

        return result;
    }

    /**
     * 构建子表行投影（系统列 + 用户子列）。
     * <p>
     * 子表系统列投影：id, parent_record_id, create_time, create_by, update_time, update_by。
     * 不含 deleted / tenant_id / version（子表暂不与主表共用乐观锁）。
     * </p>
     */
    List<String> buildSubTableProjection(List<SubFieldMeta> subFields) {
        List<String> columns = new ArrayList<>(List.of(
                "id", "parent_record_id", "create_time", "create_by", "update_time", "update_by"
        ));

        for (SubFieldMeta meta : subFields) {
            columns.add(meta.physicalCol());
        }

        return columns;
    }

    /**
     * 解析子表映射 JSON。
     */
    Map<String, String> parseSubTableMapping(String subTableMappingJson) {
        if (subTableMappingJson == null || subTableMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(subTableMappingJson,
                    new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse sub-table mapping JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 数据范围解析：服务端权威强制，缺省依赖时按登录态降级（I2）。 */
    DataScopeFilter resolveDataScope(LoginUser loginUser) {
        return FormDataScopeSupport.resolve(loginUser, loginContextProvider, deptScopeProvider);
    }
}
