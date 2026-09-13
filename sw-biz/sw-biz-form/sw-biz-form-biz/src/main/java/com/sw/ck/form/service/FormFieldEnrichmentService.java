package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.storage.api.StorageFacade;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I2 写路径统一增补/闸门服务：提交与更新共用同一管线（方向 §4.1—§4.6 服务端权威）。
 *
 * <ol>
 *   <li><b>字段编辑权限闸门</b>：构造请求携带无 edit 权字段 → 整请求拒绝（1105）。</li>
 *   <li><b>公式服务端重算</b>：剥离客户端公式值 → 按冻结 expression 以当前载荷重算并回填
 *       （BigDecimal，scale 6）；引用字段为空则结果为空。客户端篡改不成为正式结果。</li>
 *   <li><b>USER/DEPT 对象校验</b>：值必须是当前租户内启用的真实用户/部门 ID
 *       （经 UserQueryFacade/DeptQueryFacade；失效、越权、跨租户对象拒绝）。</li>
 *   <li><b>DATASOURCE 对象解析</b>：客户端只送稳定 value；服务端执行版本化契约、
 *       按 valueField 匹配真实行并回填 displayField，落 {value,display,queryKey,version} JSON。</li>
 * </ol>
 * TABLE 子行内的 USER/DEPT/DATASOURCE 值按同口径逐行处理。
 */
@Service
public class FormFieldEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(FormFieldEnrichmentService.class);

    private final FormConfigMapper formConfigMapper;
    private final ObjectMapper objectMapper;
    private final FormulaEngine formulaEngine;
    private final FieldPermissionService fieldPermissionService;
    private final ObjectProvider<FormExtDataService> extDataService;
    private final ObjectProvider<UserQueryFacade> userQueryFacade;
    private final ObjectProvider<DeptQueryFacade> deptQueryFacade;
    private final FormDefMapper formDefMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<StorageFacade> storageFacade;

    public FormFieldEnrichmentService(FormConfigMapper formConfigMapper,
                                      ObjectMapper objectMapper,
                                      FormulaEngine formulaEngine,
                                      FieldPermissionService fieldPermissionService,
                                      ObjectProvider<FormExtDataService> extDataService,
                                      ObjectProvider<UserQueryFacade> userQueryFacade,
                                      ObjectProvider<DeptQueryFacade> deptQueryFacade,
                                      FormDefMapper formDefMapper,
                                      JdbcTemplate jdbcTemplate,
                                      ObjectProvider<StorageFacade> storageFacade) {
        this.formConfigMapper = formConfigMapper;
        this.objectMapper = objectMapper;
        this.formulaEngine = formulaEngine;
        this.fieldPermissionService = fieldPermissionService;
        this.extDataService = extDataService;
        this.userQueryFacade = userQueryFacade;
        this.deptQueryFacade = deptQueryFacade;
        this.formDefMapper = formDefMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.storageFacade = storageFacade;
    }

    /**
     * 写路径增补入口：就地修改 effectiveData（提交/更新同一口径）。
     *
     * @param formId        表单 ID（加载冻结 definition）
     * @param effectiveData 有效载荷
     */
    public void enrichForWrite(String formId, Map<String, Object> effectiveData) {
        LoginUser user = LoginUserHolder.get();
        String definitionJson = loadDefinitionJson(formId);
        if (definitionJson == null || definitionJson.isBlank()) {
            return;
        }
        JsonNode fields;
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            fields = root.get("fields");
        } catch (JsonProcessingException e) {
            throw new BaseException(FormErrorCode.SUBMIT_DEFINITION_INVALID, "表单定义解析失败");
        }
        if (fields == null || !fields.isArray()) {
            return;
        }

        // 1. 字段编辑权限闸门（先于任何外部解析，避免无权探测外部对象存在性）
        fieldPermissionService.assertEditablePayload(user,
                fieldPermissionService.parse(definitionJson), effectiveData);

        // 2. 逐类型增补
        for (JsonNode field : fields) {
            String type = field.path("type").asText();
            String name = field.path("name").asText();
            switch (type) {
                case "USER" -> enrichUser(effectiveData, name);
                case "DEPT" -> enrichDept(effectiveData, name);
                case "DATASOURCE" -> enrichDatasource(field, effectiveData, name);
                case "TABLE" -> enrichTableRows(field, effectiveData, name);
                case "REFERENCE" -> enrichReference(field, effectiveData, name);
                case "ATTACHMENT", "IMAGE" -> enrichFiles(effectiveData, name);
                default -> { }
            }
        }
        // 3. 公式重算（剥离客户端值后服务端权威计算）
        recomputeFormulas(fields, effectiveData);
    }

    /** 当前身份是否具备表单 definition 顶层动作权限。 */
    public boolean canCurrentUserPerformAction(String formId, String action) {
        return fieldPermissionService.canAction(LoginUserHolder.get(), action, loadDefinitionJson(formId));
    }

    // ==================== 各类型增补 ====================

    private void enrichUser(Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        Long userId = parseId(name, value);
        UserQueryFacade facade = userQueryFacade.getIfAvailable();
        if (facade == null) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "用户服务未装配");
        }
        List<Long> active = facade.findActiveUserIds(List.of(userId));
        if (active.isEmpty()) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段 '" + name + "' 引用的用户不存在、已停用或越权");
        }
        data.put(name, String.valueOf(userId));
    }

    private void enrichDept(Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        Long deptId = parseId(name, value);
        DeptQueryFacade facade = deptQueryFacade.getIfAvailable();
        if (facade == null) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "部门服务未装配");
        }
        List<Long> active = facade.findActiveDeptIds(List.of(deptId));
        if (active.isEmpty()) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段 '" + name + "' 引用的部门不存在、已停用或越权");
        }
        data.put(name, String.valueOf(deptId));
    }

    private void enrichDatasource(JsonNode field, Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        String stableValue;
        if (value instanceof Map<?, ?> summary) {
            Object rawValue = summary.get("value");
            if (!(rawValue instanceof String) && !(rawValue instanceof Number)) {
                throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                        "字段 '" + name + "' 的服务端摘要缺少稳定对象标识");
            }
            stableValue = String.valueOf(rawValue);
        } else if (value instanceof String || value instanceof Number) {
            stableValue = String.valueOf(value);
        } else {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段 '" + name + "' 需要稳定对象标识");
        }
        FormExtDataService service = extDataService.getIfAvailable();
        if (service == null) {
            throw new BaseException(FormErrorCode.EXT_QUERY_NOT_FOUND.getCode(), "外部数据源服务未装配");
        }
        JsonNode binding = field.path("dsBinding");
        String queryKey = binding.path("queryKey").asText();
        Integer version = binding.hasNonNull("version") ? binding.get("version").intValue() : null;
        String valueField = binding.path("valueField").asText();
        String displayField = binding.path("displayField").asText();
        String display = service.resolveSelection(queryKey, version, valueField, displayField,
                stableValue);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("value", stableValue);
        summary.put("display", display);
        summary.put("queryKey", queryKey);
        summary.put("version", version);
        data.put(name, summary);
    }

    private void enrichTableRows(JsonNode tableField, Map<String, Object> data, String name) {
        Object raw = data.get(name);
        if (!(raw instanceof List<?> rows)) {
            return;
        }
        JsonNode subFields = tableField.path("subFields");
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rowMap = (Map<String, Object>) row;
            for (JsonNode sub : subFields) {
                String subType = sub.path("type").asText();
                String subName = sub.path("name").asText();
                switch (subType) {
                    case "USER" -> enrichUser(rowMap, subName);
                    case "DEPT" -> enrichDept(rowMap, subName);
                    case "DATASOURCE" -> enrichDatasource(sub, rowMap, subName);
                    default -> { }
                }
            }
        }
    }

    /**
     * REFERENCE 对象校验：值必须是引用目标表单内当前租户可见的真实记录 ID。
     * 失效（不存在/已软删）、伪造（非 ID 结构）、跨租户对象一律拒绝（I2 方向 §4.1）。
     */
    private void enrichReference(JsonNode field, Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        if (!(value instanceof String refId) || refId.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段 '" + name + "' 需要引用记录 ID");
        }
        String targetFormKey = field.path("targetFormId").asText("");
        if (targetFormKey.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_DEFINITION_INVALID,
                    "字段 '" + name + "' 缺少 targetFormId");
        }
        FormDefEntity target = formDefMapper.selectOne(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getFormKey, targetFormKey)
                .eq(FormDefEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (target == null || target.getPhysicalTableName() == null || target.getPhysicalTableName().isBlank()) {
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "字段 '" + name + "' 的引用目标表单不存在或未发布");
        }
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getTenantId() == null) {
            // 无租户上下文 fail closed：引用记录按租户隔离，缺失即拒绝（不回落租户 0）
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "字段 '" + name + "' 引用的记录不存在或不可见");
        }
        long tenantId = user.getTenantId();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + target.getPhysicalTableName()
                        + "\" WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?",
                Integer.class, refId, tenantId);
        if (count == null || count == 0) {
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "字段 '" + name + "' 引用的记录不存在或不可见");
        }
    }

    /**
     * ATTACHMENT/IMAGE 文件校验：每个 storageKey 必须真实存在且未删除。
     */
    private void enrichFiles(Map<String, Object> data, String name) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        if (!(value instanceof List<?> list)) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段 '" + name + "' 需要文件列表");
        }
        StorageFacade storage = storageFacade.getIfAvailable();
        if (storage == null) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "文件存储服务未装配");
        }
        for (Object key : list) {
            if (!(key instanceof String storageKey) || storageKey.isBlank() || !storage.exists(storageKey)) {
                throw new BaseException(FormErrorCode.ATTACHMENT_FILE_NOT_FOUND,
                        "字段 '" + name + "' 引用的文件不存在或不可见");
            }
        }
    }

    /**
     * 公式重算：剥离客户端值 → 冻结 expression + 当前载荷 → BigDecimal(scale 6) 回填。
     */
    private void recomputeFormulas(JsonNode fields, Map<String, Object> data) {
        for (JsonNode field : fields) {
            if (!"FORMULA".equals(field.path("type").asText())) {
                continue;
            }
            String name = field.path("name").asText();
            String expression = field.path("expression").asText();
            data.remove(name); // 客户端公式值一律不消费
            BigDecimal result = formulaEngine.evaluate(expression, data);
            if (result != null) {
                data.put(name, result);
            }
        }
    }

    // ==================== 工具 ====================

    private boolean isEmpty(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private Long parseId(String fieldName, Object value) {
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段 '" + fieldName + "' 需要数字型对象 ID");
        }
    }

    private String loadDefinitionJson(String formId) {
        LambdaQueryWrapper<com.sw.ck.form.entity.FormConfigEntity> query =
                Wrappers.lambdaQuery(com.sw.ck.form.entity.FormConfigEntity.class)
                        .eq(com.sw.ck.form.entity.FormConfigEntity::getFormId, formId)
                        .isNull(com.sw.ck.form.entity.FormConfigEntity::getParentTable);
        var configs = formConfigMapper.selectList(query);
        return configs == null || configs.isEmpty() ? null : configs.get(0).getDefinition();
    }
}
