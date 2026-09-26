package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        Map<String, String> labels = collectFieldLabels(fields);
        fieldPermissionService.assertEditablePayload(user,
                fieldPermissionService.parse(definitionJson), effectiveData, labels);

        // 2. REFERENCE 目标加锁（按确定锁序批量加锁，先于取值校验）
        //    与删除路径共用锁身份（租户 + 物理表 + 记录 id）：本事务持有父行锁期间，
        //    任何删除都无法把该父行软删，因此“校验通过”在提交时依然成立。
        Map<DynamicTableSql.LockTarget, Boolean> liveTargets = lockReferenceTargets(fields, effectiveData);

        // 3. 逐类型增补
        for (JsonNode field : fields) {
            String type = field.path("type").asText();
            String name = field.path("name").asText();
            String display = displayOf(field, name);
            switch (type) {
                case "USER" -> enrichUser(effectiveData, name, display);
                case "DEPT" -> enrichDept(effectiveData, name, display);
                case "DATASOURCE" -> enrichDatasource(field, effectiveData, name, display);
                case "TABLE" -> enrichTableRows(field, effectiveData, name, display, liveTargets);
                case "REFERENCE" -> enrichReference(field, effectiveData, name, display, liveTargets);
                case "ATTACHMENT", "IMAGE" -> enrichFiles(effectiveData, name, display);
                default -> { }
            }
        }
        // 4. 公式重算（剥离客户端值后服务端权威计算）
        recomputeFormulas(fields, effectiveData);
    }

    // ==================== REFERENCE 目标加锁 ====================

    /**
     * 收集本次载荷中的全部 REFERENCE 目标（主表字段 + TABLE 子行子字段），
     * 按 (物理表, 记录 id) 升序**确定锁序**批量加锁。
     *
     * @return 目标 → 是否存活（未删除且属当前租户）
     */
    private Map<DynamicTableSql.LockTarget, Boolean> lockReferenceTargets(JsonNode fields,
                                                                          Map<String, Object> data) {
        List<DynamicTableSql.LockTarget> targets = new ArrayList<>();
        collectMainReferenceTargets(fields, data, targets);
        collectSubRowReferenceTargets(fields, data, targets);
        if (targets.isEmpty()) {
            return Map.of();
        }
        LoginUser user = requireTenantContext(null);
        Map<DynamicTableSql.LockTarget, Boolean> live =
                DynamicTableSql.tryLockLiveRows(jdbcTemplate, targets, user.getTenantId());
        log.debug("REFERENCE 目标加锁完成: targets={}, live={}", targets.size(),
                live.values().stream().filter(Boolean::booleanValue).count());
        return live;
    }

    private void collectMainReferenceTargets(JsonNode fields, Map<String, Object> data,
                                             List<DynamicTableSql.LockTarget> sink) {
        for (JsonNode field : fields) {
            if (!"REFERENCE".equals(field.path("type").asText())) {
                continue;
            }
            String name = field.path("name").asText();
            String refId = referenceIdOrNull(data.get(name));
            if (refId == null) {
                continue;
            }
            sink.add(new DynamicTableSql.LockTarget(
                    resolveTargetTable(field, displayOf(field, name)), refId));
        }
    }

    private void collectSubRowReferenceTargets(JsonNode fields, Map<String, Object> data,
                                               List<DynamicTableSql.LockTarget> sink) {
        for (JsonNode field : fields) {
            if (!"TABLE".equals(field.path("type").asText())) {
                continue;
            }
            Object raw = data.get(field.path("name").asText());
            if (!(raw instanceof List<?> rows)) {
                continue;
            }
            for (JsonNode sub : field.path("subFields")) {
                if (!"REFERENCE".equals(sub.path("type").asText())) {
                    continue;
                }
                String subName = sub.path("name").asText();
                String subDisplay = displayOf(sub, subName);
                for (Object item : rows) {
                    if (!(item instanceof Map<?, ?> row)) {
                        continue;
                    }
                    String refId = referenceIdOrNull(row.get(subName));
                    if (refId == null) {
                        continue;
                    }
                    sink.add(new DynamicTableSql.LockTarget(
                            resolveTargetTable(sub, subDisplay), refId));
                }
            }
        }
    }

    /** REFERENCE 值的 id 形态（非字符串/空白返回 null，由取值校验给出类型错误）。 */
    private static String referenceIdOrNull(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    /**
     * 解析 REFERENCE 字段的物理目标表；配置缺失/目标未发布一律拒绝。
     */
    private String resolveTargetTable(JsonNode field, String fieldDisplay) {
        String targetFormKey = field.path("targetFormId").asText("");
        if (targetFormKey.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_DEFINITION_INVALID,
                    "配置错误：字段「" + fieldDisplay + "」未指定引用目标，请联系管理员");
        }
        FormDefEntity target = formDefMapper.selectOne(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getFormKey, targetFormKey)
                .eq(FormDefEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (target == null || target.getPhysicalTableName() == null || target.getPhysicalTableName().isBlank()) {
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "字段「" + fieldDisplay + "」的引用目标表单不存在或未发布，请联系管理员");
        }
        if (!DynamicTableSql.isValidTableName(target.getPhysicalTableName())) {
            log.error("Reference target form '{}' has invalid physical table name", targetFormKey);
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "字段「" + fieldDisplay + "」的引用目标表单数据表配置异常，请联系管理员");
        }
        return target.getPhysicalTableName();
    }

    /** 取当前租户上下文；缺失即 fail closed（不回落租户 0）。 */
    private static LoginUser requireTenantContext(String fieldDisplay) {
        LoginUser user = LoginUserHolder.get();
        if (user == null) {
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "缺少租户上下文，已拒绝引用校验");
        }
        if (user.getTenantId() == null && fieldDisplay != null) {
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "字段「" + fieldDisplay + "」引用的记录不存在或不可见，请重新选择");
        }
        return user;
    }

    /**
     * 字段键 → 显示名映射（P61 阶段 C）。
     * <p>设计者在 definition 里填写的 {@code label} 是用户可读名称；未填写时回退字段键，
     * 保证提示永远有名称可用。</p>
     */
    public static Map<String, String> collectFieldLabels(JsonNode fields) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        if (fields == null || !fields.isArray()) {
            return labels;
        }
        for (JsonNode field : fields) {
            String key = field.path("name").asText();
            if (key.isBlank()) {
                continue;
            }
            labels.put(key, displayOf(field, key));
            JsonNode subs = field.get("subFields");
            if (subs != null && subs.isArray()) {
                for (JsonNode sub : subs) {
                    String subKey = sub.path("name").asText();
                    if (!subKey.isBlank()) {
                        labels.put(subKey, displayOf(sub, subKey));
                    }
                }
            }
        }
        return labels;
    }

    /** 读取字段显示名：优先 {@code label}，缺省回退字段键。 */
    private static String displayOf(JsonNode field, String fallbackKey) {
        String label = field.path("label").asText("");
        return label.isBlank() ? fallbackKey : label;
    }

    /** 当前身份是否具备表单 definition 顶层动作权限。 */
    public boolean canCurrentUserPerformAction(String formId, String action) {
        return fieldPermissionService.canAction(LoginUserHolder.get(), action, loadDefinitionJson(formId));
    }

    // ==================== 各类型增补 ====================

    private void enrichUser(Map<String, Object> data, String name, String fieldDisplay) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        Long userId = parseId(name, fieldDisplay, value);
        UserQueryFacade facade = userQueryFacade.getIfAvailable();
        if (facade == null) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "用户服务未装配");
        }
        Optional<List<Long>> active = facade.findActiveUserIds(List.of(userId));
        if (isUnusable(active)) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段「" + fieldDisplay + "」引用的人员不存在、已停用或无权访问");
        }
        data.put(name, String.valueOf(userId));
    }

    private void enrichDept(Map<String, Object> data, String name, String fieldDisplay) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        Long deptId = parseId(name, fieldDisplay, value);
        DeptQueryFacade facade = deptQueryFacade.getIfAvailable();
        if (facade == null) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "部门服务未装配");
        }
        Optional<List<Long>> active = facade.findActiveDeptIds(List.of(deptId));
        if (isUnusable(active)) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段「" + fieldDisplay + "」引用的部门不存在、已停用或无权访问");
        }
        data.put(name, String.valueOf(deptId));
    }

    private void enrichDatasource(JsonNode field, Map<String, Object> data, String name, String fieldDisplay) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        String stableValue;
        if (value instanceof Map<?, ?> summary) {
            Object rawValue = summary.get("value");
            if (!(rawValue instanceof String) && !(rawValue instanceof Number)) {
                throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                        "字段「" + fieldDisplay + "」的选择结果无效，请重新选择");
            }
            stableValue = String.valueOf(rawValue);
        } else if (value instanceof String || value instanceof Number) {
            stableValue = String.valueOf(value);
        } else {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段「" + fieldDisplay + "」需要选择一条有效记录");
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

    private void enrichTableRows(JsonNode tableField, Map<String, Object> data, String name,
                                 String fieldDisplay,
                                 Map<DynamicTableSql.LockTarget, Boolean> liveTargets) {
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
                String subDisplay = displayOf(sub, subName);
                switch (subType) {
                    case "USER" -> enrichUser(rowMap, subName, subDisplay);
                    case "DEPT" -> enrichDept(rowMap, subName, subDisplay);
                    case "DATASOURCE" -> enrichDatasource(sub, rowMap, subName, subDisplay);
                    case "REFERENCE" -> enrichReference(sub, rowMap, subName, subDisplay, liveTargets);
                    default -> { }
                }
            }
        }
    }

    /**
     * REFERENCE 对象校验：值必须是引用目标表单内当前租户可见的真实记录 ID。
     * 失效（不存在/已软删）、伪造（非 ID 结构）、跨租户对象一律拒绝（I2 方向 §4.1）。
     *
     * <p>存活判定来自本次事务持有的父行锁（{@link #lockReferenceTargets}）：
     * 锁在本事务提交前一直有效，因此校验通过即意味着并发删除无法在本事务内抢先把父行软删。</p>
     */
    private void enrichReference(JsonNode field, Map<String, Object> data, String name, String fieldDisplay,
                                 Map<DynamicTableSql.LockTarget, Boolean> liveTargets) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        if (!(value instanceof String refId) || refId.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段「" + fieldDisplay + "」需要选择一条有效记录");
        }
        String targetTable = resolveTargetTable(field, fieldDisplay);
        requireTenantContext(fieldDisplay);
        DynamicTableSql.LockTarget target = new DynamicTableSql.LockTarget(targetTable, refId);
        if (!Boolean.TRUE.equals(liveTargets.get(target))) {
            throw new BaseException(FormErrorCode.REFERENCE_OBJECT_NOT_FOUND,
                    "字段「" + fieldDisplay + "」引用的记录不存在或不可见，请重新选择");
        }
    }

    /**
     * ATTACHMENT/IMAGE 文件校验：每个 storageKey 必须真实存在且未删除。
     */
    private void enrichFiles(Map<String, Object> data, String name, String fieldDisplay) {
        Object value = data.get(name);
        if (isEmpty(value)) {
            return;
        }
        if (!(value instanceof List<?> list)) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段「" + fieldDisplay + "」需要上传文件");
        }
        StorageFacade storage = storageFacade.getIfAvailable();
        if (storage == null) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "文件存储服务未装配");
        }
        for (Object key : list) {
            if (!(key instanceof String storageKey) || storageKey.isBlank()
                    || !fileExists(storage, storageKey)) {
                throw new BaseException(FormErrorCode.ATTACHMENT_FILE_NOT_FOUND,
                        "字段「" + fieldDisplay + "」引用的文件不存在或不可见，请重新上传");
            }
        }
    }

    /**
     * 引用对象存在性判定（fail closed）：empty 表示缺少租户上下文、查询未执行，
     * 与 present 空集合（已执行且零匹配）一并视为“目标不可用”，拒绝放行。
     */
    private static boolean isUnusable(Optional<List<Long>> matched) {
        if (matched.isEmpty()) {
            return true;
        }
        return matched.get().isEmpty();
    }

    /**
     * 文件存在性判定：exists 契约恒 present（含 storageKey 为空 → false）；
     * 返回 empty 属契约不可能态，按契约违约处理（不放行未经验证的引用）。
     */
    private static boolean fileExists(StorageFacade storage, String storageKey) {
        return storage.exists(storageKey).orElseThrow(() ->
                new IllegalStateException("文件存储服务未返回文件存在性判定: storageKey=" + storageKey));
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

    private Long parseId(String fieldName, String fieldDisplay, Object value) {
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_TYPE_MISMATCH,
                    "字段「" + fieldDisplay + "」需要选择具体的人员或部门");
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
