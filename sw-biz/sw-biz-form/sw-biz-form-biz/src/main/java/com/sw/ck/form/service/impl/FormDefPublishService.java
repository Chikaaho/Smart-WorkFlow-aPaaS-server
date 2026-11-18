package com.sw.ck.form.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.DynamicTableManager;
import com.sw.ck.form.dynamic.FieldSpec;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.dynamic.FormTableSpec;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormSnapshotEntity;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.service.FieldPermissionService;
import com.sw.ck.form.service.FormVisibilityRules;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 表单发布编排：首发布（definition 唯一字段真源 → 校验先行 → 创建动态宽表 →
 * 回填元数据与 sw_form_config 行 → 存快照）与同表新版本（仅增量加列、TABLE
 * 结构签名不变才允许、递增 formVersion）。DDL 不可回滚，因此校验先行；
 * 列表配置随快照冻结，历史读取不依赖现行配置。
 */
@Service
@Log4j2
class FormDefPublishService {

    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private FormConfigMapper formConfigMapper;
    @Autowired
    private FormSnapshotMapper formSnapshotMapper;
    @Autowired
    private DynamicTableManager dynamicTableManager;
    @Autowired
    private FormIdGenerator idGenerator;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FormVisibilityRules visibilityRules;
    @Autowired
    private FieldPermissionService fieldPermissionService;
    @Autowired
    private FormDefDefinitionValidator definitionValidator;
    @Autowired
    private FormDefLifecycleService lifecycleService;
    @Autowired
    private FormDefQueryService queryService;

    void publish(String formId) {
        // —— Step 1: 加载并校验状态 ——
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FormStatusEnum.DRAFT.getCode().equals(entity.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_ALREADY_PUBLISHED, "表单已发布，不能重复发布");
        }

        // —— Step 2: 加载 config.definition 并解析校验字段（唯一字段真源） ——
        LambdaQueryWrapper<FormConfigEntity> configQuery = Wrappers.lambdaQuery(FormConfigEntity.class)
                .eq(FormConfigEntity::getFormId, formId)
                .isNull(FormConfigEntity::getParentTable);
        FormConfigEntity config = formConfigMapper.selectOne(configQuery);
        String definitionJson = (config != null) ? config.getDefinition() : "{}";

        List<FieldSpec> fields = definitionValidator.parseAndValidateFieldsFromDefinition(definitionJson);

        // —— Step 2b: 显隐规则校验（v0.0.2：字段存在/op/logic/无环依赖） ——
        Set<String> fieldNames = fields.stream()
                .map(FieldSpec::getFieldName)
                .collect(Collectors.toSet());
        visibilityRules.parseAndValidate(definitionJson, definitionValidator.collectFieldDisplay(definitionJson));

        // —— Step 2c（I2）: 公式依赖 + 字段权限配置 + 外部数据源绑定校验 ——
        definitionValidator.validateFormulaDependencies(definitionJson, fieldNames);
        fieldPermissionService.parse(definitionJson);
        definitionValidator.validateFormEvents(definitionJson);
        definitionValidator.validateFormIntegrations(definitionJson);
        definitionValidator.validateDatasourceBindings(definitionJson);
        String frozenDefinition = freezeSnapshotDefinition(formId, definitionJson);

        // —— Step 3: 校验字段名白名单（复用 ColumnValidation） ——
        if (entity.getLogicalTableName() != null && !entity.getLogicalTableName().isBlank()) {
            ColumnValidation.validateColumnName(entity.getLogicalTableName());
        }
        Set<String> columnNames = new HashSet<>();
        for (FieldSpec field : fields) {
            if (field.getFieldType() == FieldType.TABLE) continue; // 不产生列
            if (field.getFieldType() == FieldType.LABEL) continue; // v0.0.2：说明文字不产生列
            String physicalName = field.getPhysicalColumnName();
            try {
                ColumnValidation.validateColumnName(physicalName);
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.INVALID_COLUMN_NAME,
                        "存在不合法的字段标识，请按提示修改后重试");
            }
            if (!columnNames.add(physicalName)) {
                throw new BaseException(FormErrorCode.DUPLICATE_COLUMN, "字段标识重复，请修改后重试");
            }
        }

        // —— Step 4: 创建物理表（DDL 不可回滚，因此校验先行） ——
        FormTableSpec tableSpec = new FormTableSpec(false, fields);
        Map<String, String> subTableNameSink = new HashMap<>();
        String physicalTableName;
        try {
            physicalTableName = dynamicTableManager.createFormTable(tableSpec, subTableNameSink);
        } catch (Exception e) {
            log.error("Failed to create physical table for form: {}", formId, e);
            throw new BaseException(FormErrorCode.PUBLISH_FAILED, "发布失败：数据表创建未成功，请稍后重试或联系管理员");
        }
        log.info("Physical table created: {} for form: {}", physicalTableName, formId);

        // —— Step 4a: 序列化子表映射 ——
        String subTableMappingJson = null;
        if (!subTableNameSink.isEmpty()) {
            try {
                subTableMappingJson = objectMapper.writeValueAsString(subTableNameSink);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize sub-table mapping, skip: {}", e.getMessage());
            }
        }

        // —— Step 5: 回填表单元数据 + sw_form_config 的 table_name/parent_table ——
        entity.setPhysicalTableName(physicalTableName);
        entity.setStatus(FormStatusEnum.PUBLISHED.getCode());
        entity.setFormVersion(entity.getFormVersion() == null ? 1 : entity.getFormVersion() + 1);
        entity.setSubTableMapping(subTableMappingJson);
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);

        // 回填主表单的 sw_form_config 行：table_name = physicalTableName
        if (config != null) {
            config.setTableName(physicalTableName);
            // parent_table 对主表单留空
            formConfigMapper.updateById(config);
        }

        // 回填每个 TABLE 子表的 sw_form_config 行：parent_table = 主表 table_name
        for (Map.Entry<String, String> entry : subTableNameSink.entrySet()) {
            String subFieldName = entry.getKey();
            String subTableName = entry.getValue();
            FormConfigEntity subConfig = new FormConfigEntity();
            subConfig.setId(idGenerator.generate());
            subConfig.setFormId(formId);
            subConfig.setTableName(subTableName);
            subConfig.setParentTable(physicalTableName);
            // 子表 definition = 该 TABLE 字段的 subFields 序列化
            String subDefinition = buildSubTableDefinition(fields, subFieldName);
            subConfig.setDefinition(subDefinition);
            subConfig.setCreateTime(LocalDateTime.now());
            subConfig.setUpdateTime(LocalDateTime.now());
            subConfig.setDeleted(0);
            subConfig.setVersion(0L);
            formConfigMapper.insert(subConfig);
        }

        // —— Step 6: 存快照 ——
        FormSnapshotEntity snapshot = new FormSnapshotEntity();
        snapshot.setId(idGenerator.generate());
        snapshot.setFormId(formId);
        snapshot.setFormVersion(entity.getFormVersion());
        snapshot.setDefinition(frozenDefinition);
        snapshot.setCreateTime(LocalDateTime.now());
        snapshot.setUpdateTime(LocalDateTime.now());
        snapshot.setDeleted(0);
        snapshot.setVersion(0L);
        formSnapshotMapper.insert(snapshot);

        log.info("Form published: id={}, formKey={}, physicalTable={}, version={}",
                formId, entity.getFormKey(), physicalTableName, entity.getFormVersion());
    }

    /** 将发布时的管理员列表配置一起冻结，历史读取不再依赖现行配置。 */
    private String freezeSnapshotDefinition(String formId, String definition) {
        if (lifecycleService == null) return definition;
        String listConfig = lifecycleService.getListConfig(formId);
        if (listConfig == null || listConfig.isBlank()) return definition;
        try {
            ObjectNode frozen = (ObjectNode) objectMapper.readTree(definition);
            frozen.set("listConfig", objectMapper.readTree(listConfig));
            return objectMapper.writeValueAsString(frozen);
        } catch (JsonProcessingException | ClassCastException e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                    "发布快照配置无效，请检查表单和列表配置后重试");
        }
    }

    void publishNewVersion(String formId, String definition) {
        FormDefEntity entity = formDefMapper.selectById(formId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND);
        }
        if (!FormStatusEnum.PUBLISHED.getCode().equals(entity.getStatus())
                && !FormStatusEnum.DISABLED.getCode().equals(entity.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_NOT_PUBLISHED,
                    "仅已发布或已停用表单可发布新版本");
        }
        if (entity.getPhysicalTableName() == null || entity.getPhysicalTableName().isBlank()) {
            throw new BaseException(FormErrorCode.PUBLISH_FAILED, "表单缺少既有物理表，不能发布新版本");
        }

        definitionValidator.validateLayoutDefinition(definition);
        List<FieldSpec> fields = definitionValidator.parseAndValidateFieldsFromDefinition(definition);
        Set<String> fieldNames = fields.stream()
                .map(FieldSpec::getFieldName)
                .collect(Collectors.toSet());
        visibilityRules.parseAndValidate(definition, definitionValidator.collectFieldDisplay(definition));
        definitionValidator.validateFormulaDependencies(definition, fieldNames);
        fieldPermissionService.parse(definition);
        definitionValidator.validateFormEvents(definition);
        definitionValidator.validateFormIntegrations(definition);
        definitionValidator.validateDatasourceBindings(definition);
        String frozenDefinition = freezeSnapshotDefinition(formId, definition);

        // 先完成全部定义校验，再执行不可回滚的增量 DDL；新版本只允许复用原表。
        // 明细表（TABLE）不产生主表列：结构未变化时允许随新版本保留（打印模板等
        // 定义级配置需要能随版本发布），新增/变更明细表仍拒绝（F2-6 验收实测暴露）。
        Map<String, String> previousTables = tableSignatures(queryService.getDefinitionById(formId));
        for (FieldSpec field : fields) {
            if (field.getFieldType() == FieldType.TABLE) {
                String signature = tableSignature(definition, field.getFieldName());
                if (!signature.equals(previousTables.get(field.getFieldName()))) {
                    throw new BaseException(FormErrorCode.DEFINITION_INVALID,
                            "新版本暂不支持新增或变更 TABLE 字段");
                }
                continue;
            }
            if (field.getFieldType() != FieldType.LABEL) {
                dynamicTableManager.addColumn(entity.getPhysicalTableName(), field);
            }
        }

        FormConfigEntity config = formConfigMapper.selectOne(
                Wrappers.lambdaQuery(FormConfigEntity.class)
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable));
        if (config == null) {
            throw new BaseException(FormErrorCode.CONFIG_NOT_FOUND, "表单配置不存在，不能发布新版本");
        }
        config.setDefinition(definition);
        config.setUpdateTime(LocalDateTime.now());
        formConfigMapper.updateById(config);

        int newVersion = entity.getFormVersion() == null ? 1 : entity.getFormVersion() + 1;
        entity.setFormVersion(newVersion);
        entity.setStatus(FormStatusEnum.PUBLISHED.getCode());
        entity.setUpdateTime(LocalDateTime.now());
        formDefMapper.updateById(entity);

        FormSnapshotEntity snapshot = new FormSnapshotEntity();
        snapshot.setId(idGenerator.generate());
        snapshot.setFormId(formId);
        snapshot.setFormVersion(newVersion);
        snapshot.setDefinition(frozenDefinition);
        snapshot.setCreateTime(LocalDateTime.now());
        snapshot.setUpdateTime(LocalDateTime.now());
        snapshot.setDeleted(0);
        snapshot.setVersion(0L);
        formSnapshotMapper.insert(snapshot);

        log.info("Form new version published: id={}, formKey={}, physicalTable={}, version={}",
                formId, entity.getFormKey(), entity.getPhysicalTableName(), newVersion);
    }

    /** 解析定义中的全部 TABLE 字段结构签名（字段名 → 子字段 name:type 有序串）。 */
    private Map<String, String> tableSignatures(String definitionJson) {
        Map<String, String> result = new LinkedHashMap<>();
        if (definitionJson == null || definitionJson.isBlank()) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fields = root == null ? null : root.get("fields");
            if (fields == null || !fields.isArray()) {
                return result;
            }
            for (JsonNode field : fields) {
                if (!FieldType.TABLE.name().equals(field.path("type").asText())) {
                    continue;
                }
                String name = field.path("name").asText(null);
                if (name == null) {
                    continue;
                }
                StringBuilder signature = new StringBuilder(name).append('|');
                JsonNode subFields = field.get("subFields");
                if (subFields != null && subFields.isArray()) {
                    for (JsonNode sub : subFields) {
                        signature.append(sub.path("name").asText("")).append(':')
                                .append(sub.path("type").asText(FieldType.TEXT.name())).append(',');
                    }
                }
                result.put(name, signature.toString());
            }
            return result;
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.DEFINITION_INVALID, "表单定义配置异常，请检查后重试");
        }
    }

    /** 单个 TABLE 字段的结构签名；定义中不存在返回空串（视为新增明细表）。 */
    private String tableSignature(String definitionJson, String fieldName) {
        return tableSignatures(definitionJson).getOrDefault(fieldName, "");
    }

    /**
     * 构建子表的 definition JSON（从父表单 fields 中提取指定 TABLE 字段的 subFields）。
     */
    private String buildSubTableDefinition(List<FieldSpec> masterFields, String tableFieldName) {
        for (FieldSpec f : masterFields) {
            if (f.getFieldType() == FieldType.TABLE && f.getFieldName().equals(tableFieldName)) {
                try {
                    return objectMapper.writeValueAsString(f.getSubFields());
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize sub-table definition for '{}': {}", tableFieldName, e.getMessage());
                    return "[]";
                }
            }
        }
        return "[]";
    }
}
