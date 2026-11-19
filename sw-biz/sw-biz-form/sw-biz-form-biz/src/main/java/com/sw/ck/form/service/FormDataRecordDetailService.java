package com.sw.ck.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.jdbc.JdbcVendorSupport;
import com.sw.ck.form.api.port.FormRecordAccessPort;
import com.sw.ck.form.api.vo.FormDefVO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 表单单条记录查询：详情（含子表行，供编辑回显）、系统上下文全量读取
 * （P63 跨模块读接缝，无登录态）、附件可读判定与 REFERENCE 存在性校验。
 * 裸 SQL 全部携带 {@code deleted=0 AND tenant_id=?}，表名/列名走受控入口校验。
 */
@Service
@Log4j2
class FormDataRecordDetailService {

    @Autowired
    private FormDefService formDefService;
    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FormDataQuerySupport support;

    /** 应用范围访问闸门（可选注入；不注入时不受本闸门约束）。 */
    @Autowired(required = false)
    private FormAppAccessGuard appAccessGuard;

    @Autowired(required = false)
    private FormFieldEnrichmentService enrichment;

    // ==== I2 依赖（可选注入；缺省时按登录态降级解析） ====

    @Autowired(required = false)
    private FormRecordAccessPort formRecordAccessPort;

    /**
     * 查询单条记录详情（含子表行，供编辑回显）。
     * <p>
     * 返回主记录的 id + 审计列 + 业务列 + version（乐观锁用）+
     * 各 TABLE 子表的行列表。与列表查询不同，此处包含 version 字段。
     * </p>
     *
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     * @return 主记录字段 + 子表行（key=TABLE字段逻辑名）
     */
    Map<String, Object> getRecordDetail(String formKey, String recordId) {
        // —— Step 1: 获取当前用户 ——
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        Long tenantId = loginUser.getTenantId();

        // —— Step 2: 解析 formKey → FormDefVO ——
        FormDefVO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (!FormStatusEnum.PUBLISHED.getCode().equals(formDef.getStatus()) && !FormStatusEnum.DISABLED.getCode().equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 未发布，不能查询");
        }
        if (enrichment != null) {
            enrichment.assertCurrentUserCanPerformAction(formDef.getId(), "view");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST,
                    "该表单尚未完成数据表初始化，请联系管理员处理");
        }
        support.validateTableName(tableName);

        // —— Step 2.5: 应用范围闸门（记录级） ——
        // 应用不可见/停用时不允许“新访问”，但保留合法历史访问：本人提交的记录，或
        // 与引用该记录的流程实例存在参与关系（发起人/办理过/节点参与人快照）。
        if (appAccessGuard != null) {
            appAccessGuard.requireRecordAccess(formKey, formDef.getAppId(), tableName, recordId);
        }

        // —— Step 3: 加载字段元数据 ——
        Map<String, FieldType> fieldTypeMap = support.loadFieldTypeMap(formDef.getId());
        Map<String, List<SubFieldMeta>> tableSubFields = support.loadTableSubFields(formDef.getId());

        // —— I2: 字段查看权限 + 记录数据范围（服务端权威强制） ——
        Set<String> viewDenied = support.viewDeniedFields(loginUser, formDef.getId());

        // —— Step 4: 构建详情投影（含 version；剔除无 view 权字段） ——
        List<String> projectionColumns = buildDetailProjection(fieldTypeMap, viewDenied);

        // —— Step 5: 查询主记录（数据范围条件与列表同口径强制） ——
        String columns = projectionColumns.stream()
                .map(DynamicTableSql::quote)
                .collect(Collectors.joining(", "));
        StringBuilder detailWhere = new StringBuilder("\"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?");
        List<Object> detailParams = new ArrayList<>(List.of(recordId, tenantId));
        DataScopeFilter detailScope = support.resolveDataScope(loginUser);
        FormDataScopeSupport.appendWhere(detailWhere, detailParams, detailScope);
        String sql = "SELECT " + columns + " FROM " + DynamicTableSql.quote(tableName)
                + " WHERE " + detailWhere;

        List<Map<String, Object>> records;
        try {
            records = DynamicTableSql.query(jdbcTemplate, tableName, sql, detailParams.toArray());
        } catch (Exception e) {
            log.error("Detail query failed: table={}, recordId={}", tableName, recordId, e);
            throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "查询记录时系统未能完成，请稍后重试");
        }

        if ((records == null || records.isEmpty()) && formRecordAccessPort != null
                && isRestrictiveScope(detailScope)) {
            // 数据范围遮蔽后的合法办理人通道：记录被流程实例引用且当前用户为该实例
            // 发起人/办理人/参与人时，按记录 ID 重查（仅该行，不放大表单级范围）。
            if (formRecordAccessPort.isProcessParticipant(tenantId, loginUser.getUserId(),
                    formKey, recordId).orElse(false)) {
                String participantSql = "SELECT " + columns + " FROM " + DynamicTableSql.quote(tableName)
                        + " WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?";
                records = DynamicTableSql.query(jdbcTemplate, tableName, participantSql,
                        new Object[]{recordId, tenantId});
            }
        }

        if (records == null || records.isEmpty()) {
            throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        }

        Map<String, Object> result = new LinkedHashMap<>(records.get(0));

        // —— Step 6: 加载子表行 ——
        FormDefEntity formDefEntity = formDefMapper.selectById(formDef.getId());
        if (formDefEntity != null) {
            Map<String, String> subTableMapping = support.parseSubTableMapping(formDefEntity.getSubTableMapping());

            for (Map.Entry<String, String> entry : subTableMapping.entrySet()) {
                String tableFieldName = entry.getKey();
                String subTableName = entry.getValue();

                if (subTableName == null || subTableName.isBlank()) continue;
                if (!DynamicTableSql.isValidTableName(subTableName)) {
                    log.error("Invalid sub-table name '{}' in subTableMapping", subTableName);
                    throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                            "该表单的子表配置异常，请联系管理员处理");
                }

                // 获取子表字段定义
                List<SubFieldMeta> subFields = tableSubFields.getOrDefault(tableFieldName, List.of());

                // 构建子表投影（I2：剔除无 view 权子字段，键 tableField.subField）
                List<String> subProjection = support.buildSubTableProjection(subFields).stream()
                        .filter(c -> subFields.stream()
                                .noneMatch(m -> m.physicalCol().equals(c)
                                        && viewDenied.contains(tableFieldName + "." + m.name())))
                        .toList();

                // 查询子表行
                String subColumns = subProjection.stream()
                        .map(DynamicTableSql::quote)
                        .collect(Collectors.joining(", "));
                String subSql = "SELECT " + subColumns + " FROM " + DynamicTableSql.quote(subTableName)
                        + " WHERE \"parent_record_id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?"
                        + " ORDER BY \"create_time\" ASC";

                List<Map<String, Object>> subRows;
                try {
                    subRows = DynamicTableSql.query(jdbcTemplate, subTableName, subSql, recordId, tenantId);
                } catch (BaseException e) {
                    // 受控入口契约违规：原样上抛，不降级为空列表
                    throw e;
                } catch (Exception e) {
                    // fail closed：子表读取失败不得伪装成“无子行”
                    log.error("Sub-table query failed for '{}': {}", subTableName, e.getMessage(), e);
                    throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                            "读取子表数据时系统未能完成，请稍后重试");
                }

                result.put(tableFieldName, subRows != null ? subRows : List.of());
            }
        }

        log.debug("Record detail: formKey={}, recordId={}, subTables={}",
                formKey, recordId, result.keySet().stream()
                        .filter(k -> tableSubFields.containsKey(k)).count());
        return result;
    }

    /** 附件读取沿用用户详情投影；记录可读不代表能读取未关联或不可见字段中的文件。 */
    boolean canCurrentUserAccessAttachment(String formKey, String recordId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return false;
        }
        Map<String, Object> record = getRecordDetail(formKey, recordId);
        FormDefVO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            return false;
        }
        Set<String> denied = support.viewDeniedFields(LoginUserHolder.get(), formDef.getId());
        for (Map.Entry<String, FieldType> field : support.loadFieldTypeMap(formDef.getId()).entrySet()) {
            if (!denied.contains(field.getKey()) && isAttachmentType(field.getValue())
                    && referencesStorageKey(record.get(ColumnValidation.physicalColumnName(
                            field.getKey(), field.getValue())), storageKey)) {
                return true;
            }
        }
        for (Map.Entry<String, List<SubFieldMeta>> table : support.loadTableSubFields(formDef.getId()).entrySet()) {
            if (denied.contains(table.getKey()) || !(record.get(table.getKey()) instanceof List<?> rows)) {
                continue;
            }
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                for (SubFieldMeta field : table.getValue()) {
                    if (!denied.contains(table.getKey() + "." + field.name())
                            && isAttachmentType(field.type())
                            && referencesStorageKey(row.get(field.physicalCol()), storageKey)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isAttachmentType(FieldType type) {
        return type == FieldType.ATTACHMENT || type == FieldType.IMAGE;
    }

    private boolean referencesStorageKey(Object value, String storageKey) {
        if (value == null) {
            return false;
        }
        try {
            JsonNode items = value instanceof String text
                    ? objectMapper.readTree(text) : objectMapper.valueToTree(value);
            if (items == null || !items.isArray()) {
                return false;
            }
            for (JsonNode item : items) {
                if (item.isTextual() && storageKey.equals(item.asText())) {
                    return true;
                }
                if (item.isObject() && item.path("storageKey").isTextual()
                        && storageKey.equals(item.get("storageKey").asText())) {
                    return true;
                }
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Attachment reference could not be decoded; access denied");
        }
        return false;
    }

    /**
     * 系统上下文读取记录全量数据（P63 跨模块读接缝；流程引擎/调度侧无登录态）。
     * <p>
     * 与 {@link #getRecordDetail(String, String)} 的差异：显式租户、不做字段查看权限投影
     * 与数据范围（调用方为受信服务端上下文，对象可见性由流程侧既有授权承接）、
     * 子表行保留稳定行 {@code id}（来源行追溯）。
     * 裸 SQL 全部携带 {@code deleted=0 AND tenant_id=?}，表名仍走固定白名单校验。
     * </p>
     */
    Map<String, Object> getRecordDetailForSystem(Long tenantId, String formKey, String recordId) {
        if (tenantId == null || recordId == null || recordId.isBlank()) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "读取表单记录缺少租户或记录标识");
        }
        FormDefVO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null || !FormStatusEnum.PUBLISHED.getCode().equals(formDef.getStatus()) && !FormStatusEnum.DISABLED.getCode().equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在或未发布");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST,
                    "该表单尚未完成数据表初始化，请联系管理员处理");
        }
        support.validateTableName(tableName);

        Map<String, FieldType> fieldTypeMap = support.loadFieldTypeMap(formDef.getId());
        List<String> projectionColumns = new ArrayList<>();
        projectionColumns.add("id");
        for (Map.Entry<String, FieldType> entry : fieldTypeMap.entrySet()) {
            if (entry.getValue() == FieldType.TABLE || entry.getValue() == FieldType.LABEL) {
                continue;
            }
            try {
                projectionColumns.add(ColumnValidation.physicalColumnName(entry.getKey(), entry.getValue()));
            } catch (IllegalArgumentException e) {
                throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                        "该表单的字段配置异常，请联系管理员处理");
            }
        }
        String columns = projectionColumns.stream()
                .map(DynamicTableSql::quote)
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + columns + " FROM " + DynamicTableSql.quote(tableName)
                + " WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?";
        List<Map<String, Object>> records;
        try {
            records = DynamicTableSql.query(jdbcTemplate, tableName, sql, recordId, tenantId);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("System detail query failed: table={}, recordId={}", tableName, recordId, e);
            throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "查询记录时系统未能完成，请稍后重试");
        }
        if (records == null || records.isEmpty()) {
            throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        }
        Map<String, Object> result = new LinkedHashMap<>(records.get(0));

        FormDefEntity formDefEntity = formDefMapper.selectById(formDef.getId());
        if (formDefEntity != null) {
            Map<String, String> subTableMapping = support.parseSubTableMapping(formDefEntity.getSubTableMapping());
            Map<String, List<SubFieldMeta>> tableSubFields = support.loadTableSubFields(formDef.getId());
            for (Map.Entry<String, String> entry : subTableMapping.entrySet()) {
                String tableFieldName = entry.getKey();
                String subTableName = entry.getValue();
                if (subTableName == null || subTableName.isBlank()) continue;
                if (!DynamicTableSql.isValidTableName(subTableName)) {
                    log.error("Invalid sub-table name '{}' in subTableMapping", subTableName);
                    throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                            "该表单的子表配置异常，请联系管理员处理");
                }
                List<String> subProjection = new ArrayList<>();
                subProjection.add("id");
                for (SubFieldMeta meta : tableSubFields.getOrDefault(tableFieldName, List.of())) {
                    subProjection.add(meta.physicalCol());
                }
                String subColumns = subProjection.stream()
                        .map(DynamicTableSql::quote)
                        .collect(Collectors.joining(", "));
                String subSql = "SELECT " + subColumns + " FROM " + DynamicTableSql.quote(subTableName)
                        + " WHERE \"parent_record_id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?"
                        + " ORDER BY \"create_time\" ASC";
                List<Map<String, Object>> subRows;
                try {
                    subRows = DynamicTableSql.query(jdbcTemplate, subTableName, subSql, recordId, tenantId);
                } catch (BaseException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("System sub-table query failed for '{}': {}", subTableName, e.getMessage(), e);
                    throw new BaseException(FormErrorCode.DYNAMIC_TABLE_METADATA_UNAVAILABLE,
                            "读取子表数据时系统未能完成，请稍后重试");
                }
                result.put(tableFieldName, subRows != null ? subRows : List.of());
            }
        }
        return result;
    }

    /**
     * 以与详情查询相同的租户、删除和表单可见性边界检查记录是否存在。
     * <p>
     * 该方法供跨模块 REFERENCE 契约校验使用；只查询常量列，不读取业务数据，
     * 记录 ID 作为参数绑定，物理表名仍经过固定白名单校验。
     * </p>
     */
    boolean canCurrentUserAccessRecord(String formKey, String recordId) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getTenantId() == null
                || recordId == null || recordId.isBlank()) {
            return false;
        }
        FormDefVO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null || !FormStatusEnum.PUBLISHED.getCode().equals(formDef.getStatus())
                || !formDefService.isCurrentUserVisible(formKey)) {
            return false;
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || !DynamicTableSql.isValidTableName(tableName)) {
            return false;
        }
        String sql = JdbcVendorSupport.applyLimit(jdbcTemplate,
                "SELECT 1 AS present FROM " + DynamicTableSql.quote(tableName)
                        + " WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?", 1);
        try {
            List<Map<String, Object>> rows = DynamicTableSql.query(jdbcTemplate, tableName, sql,
                    recordId, loginUser.getTenantId());
            return !rows.isEmpty();
        } catch (Exception e) {
            log.warn("REFERENCE record access check failed: formKey={}, recordId={}", formKey, recordId, e);
            return false;
        }
    }

    /**
     * 数据范围是否处于“可能遮蔽”状态：非空且带用户/部门约束或恒假。
     * none 语义（无约束）不触发合法办理人回退通道。
     */
    private boolean isRestrictiveScope(DataScopeFilter scope) {
        return scope != null
                && (scope.getUserId() != null || scope.getDeptIds() != null || scope.isAlwaysFalse());
    }

    /**
     * 构建详情视图的列投影（含 version，不含 deleted/tenant_id）。
     */
    private List<String> buildDetailProjection(Map<String, FieldType> fieldTypeMap, Set<String> viewDenied) {
        // 详情投影系统列：id + 审计列 + version（比列表多 version）
        List<String> columns = new ArrayList<>(List.of(
                "id", "create_time", "create_by", "update_time", "update_by", "version"
        ));

        for (Map.Entry<String, FieldType> entry : fieldTypeMap.entrySet()) {
            FieldType ft = entry.getValue();
            if (ft == FieldType.TABLE) continue;
            if (ft == FieldType.LABEL) continue;
            if (!ft.isEnabled()) continue;
            // I2：无 view 权字段不进详情投影
            if (viewDenied.contains(entry.getKey())) continue;

            String physicalCol = DynamicTableSql.requireColumn(entry.getKey(), ft);
            columns.add(physicalCol);
        }

        return columns;
    }
}
