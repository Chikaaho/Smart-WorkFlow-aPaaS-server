package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.form.api.facade.FormDataWritebackFacade;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormDefMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P64 阶段Ⅱ（A06 隔离与准确回写）受控回写实现。
 * <p>
 * 红线与 {@link FormDataUpdateService} 同口径：列名过 {@link DynamicTableSql#requireColumn}
 * 白名单单出口、表名正则校验、值一律参数化绑定；每条 SQL 手写 {@code deleted=0 AND tenant_id=?}；
 * 子表行 UPDATE 强制 {@code parent_record_id} 防越权动他人子行；行/记录乐观版本守卫，
 * 冲突返回 {@code VERSION_CONFLICT} 挂起，不覆盖任何现有值；回写成功版本 +1。
 * 不依赖登录上下文（命令消费事务内运行），租户/操作人由请求显式携带。
 * </p>
 */
@Service
public class FormDataWritebackFacadeImpl implements FormDataWritebackFacade {

    private static final Logger log = LoggerFactory.getLogger(FormDataWritebackFacadeImpl.class);

    /** UUID v4 形态正则（防伪造 id 注入，与 FormDataUpdateService 同口径）。 */
    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final FormDefMapper formDefMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FormFieldValidator formFieldValidator;

    public FormDataWritebackFacadeImpl(FormDefMapper formDefMapper,
                                       JdbcTemplate jdbcTemplate,
                                       ObjectMapper objectMapper,
                                       FormFieldValidator formFieldValidator) {
        this.formDefMapper = formDefMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.formFieldValidator = formFieldValidator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<WritebackResult> applyWriteback(WritebackRequest request) {
        if (request == null || request.tenantId() == null
                || isBlank(request.formKey()) || isBlank(request.recordId())
                || request.fields() == null || request.fields().isEmpty()) {
            // 请求上下文缺失：查询/写入未执行
            return Optional.empty();
        }
        FormDefEntity formDef = formDefMapper.selectOne(Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, request.formKey())
                .last("limit 1"));
        if (formDef == null || formDef.getPhysicalTableName() == null
                || formDef.getPhysicalTableName().isBlank()) {
            return Optional.empty();
        }
        boolean rowMode = !isBlank(request.tableField());
        if (rowMode && (isBlank(request.rowId()) || !request.rowId().matches(UUID_PATTERN))) {
            // 行级回写必须携带合法稳定行身份（父表排序变化不错行的根基）
            throw new IllegalArgumentException("行级回写必须携带合法来源行 ID: "
                    + request.tableField());
        }
        Map<String, FormFieldValidator.FieldDef> fieldDefs =
                formFieldValidator.loadAndParseFieldDefs(formDef.getId(), Map.of());
        WritebackResult result = rowMode
                ? applyRowWriteback(formDef, fieldDefs, request)
                : applyMainWriteback(formDef, fieldDefs, request);
        return Optional.of(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> readVersion(Long tenantId, String formKey, String recordId,
                                      String tableField, String rowId) {
        if (tenantId == null || isBlank(formKey) || isBlank(recordId)) {
            return Optional.empty();
        }
        FormDefEntity formDef = formDefMapper.selectOne(Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey)
                .last("limit 1"));
        if (formDef == null || formDef.getPhysicalTableName() == null
                || formDef.getPhysicalTableName().isBlank()) {
            return Optional.empty();
        }
        if (isBlank(tableField)) {
            return Optional.ofNullable(queryVersion(formDef.getPhysicalTableName(),
                    "\"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?",
                    recordId, tenantId));
        }
        if (isBlank(rowId)) {
            return Optional.empty();
        }
        Map<String, FormFieldValidator.FieldDef> fieldDefs =
                formFieldValidator.loadAndParseFieldDefs(formDef.getId(), Map.of());
        FormFieldValidator.FieldDef tableDef = fieldDefs.get(tableField);
        if (tableDef == null || !"TABLE".equals(tableDef.type())) {
            return Optional.empty();
        }
        String subTableName = parseSubTableMapping(formDef.getSubTableMapping()).get(tableField);
        if (subTableName == null || subTableName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(queryVersion(subTableName,
                "\"id\" = ? AND \"parent_record_id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?",
                rowId, recordId, tenantId));
    }

    // ==================== 主记录字段回写 ====================

    private WritebackResult applyMainWriteback(FormDefEntity formDef,
                                               Map<String, FormFieldValidator.FieldDef> fieldDefs,
                                               WritebackRequest request) {
        String tableName = formDef.getPhysicalTableName();
        DynamicTableSql.requireTableName(tableName);
        List<String> setParts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : request.fields().entrySet()) {
            FormFieldValidator.FieldDef def = fieldDefs.get(entry.getKey());
            if (def == null || "LABEL".equals(def.type()) || "TABLE".equals(def.type())) {
                throw new IllegalArgumentException("回写字段不存在或不允许回写: " + entry.getKey());
            }
            String colName = DynamicTableSql.requireColumn(def.name(), FieldType.valueOf(def.type()));
            setParts.add(DynamicTableSql.quote(colName) + " = ?");
            params.add(serializeValue(def, entry.getValue()));
        }
        setParts.add("\"update_time\" = ?");
        params.add(LocalDateTime.now());
        setParts.add("\"update_by\" = ?");
        params.add(request.actorId());
        setParts.add("\"version\" = \"version\" + 1");

        StringBuilder where = new StringBuilder("\"id\" = ?");
        params.add(request.recordId());
        if (request.expectedRowVersion() != null) {
            where.append(" AND \"version\" = ?");
            params.add(request.expectedRowVersion());
        }
        where.append(" AND \"deleted\" = 0 AND \"tenant_id\" = ?");
        params.add(request.tenantId());

        Long currentVersion = queryVersion(tableName,
                "\"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?",
                request.recordId(), request.tenantId());
        if (currentVersion == null) {
            return new WritebackResult(WritebackResult.NOT_FOUND, null);
        }
        if (request.expectedRowVersion() != null
                && !request.expectedRowVersion().equals(currentVersion)) {
            // 版本冲突挂起：不覆盖任何现有值
            return new WritebackResult(WritebackResult.VERSION_CONFLICT, currentVersion);
        }
        int affected = DynamicTableSql.update(jdbcTemplate, tableName,
                "UPDATE " + DynamicTableSql.quote(tableName) + " SET "
                        + String.join(", ", setParts) + " WHERE " + where,
                params.toArray());
        if (affected == 0) {
            // 读取后行被并发删除/版本竞态：按冲突挂起（可诊断，不覆盖）
            return new WritebackResult(WritebackResult.VERSION_CONFLICT, currentVersion);
        }
        log.info("主记录回写完成: formKey={}, recordId={}, fields={}, version {} -> {}",
                request.formKey(), request.recordId(), request.fields().keySet(),
                currentVersion, currentVersion + 1);
        return new WritebackResult(WritebackResult.WRITTEN, currentVersion + 1);
    }

    // ==================== 子表行回写（稳定行身份） ====================

    private WritebackResult applyRowWriteback(FormDefEntity formDef,
                                              Map<String, FormFieldValidator.FieldDef> fieldDefs,
                                              WritebackRequest request) {
        String mainTableName = formDef.getPhysicalTableName();
        DynamicTableSql.requireTableName(mainTableName);
        FormFieldValidator.FieldDef tableDef = fieldDefs.get(request.tableField());
        if (tableDef == null || !"TABLE".equals(tableDef.type()) || tableDef.subFields() == null) {
            throw new IllegalArgumentException("回写目标不是合法表格字段: " + request.tableField());
        }
        String subTableName = parseSubTableMapping(formDef.getSubTableMapping())
                .get(request.tableField());
        if (subTableName == null || subTableName.isBlank()) {
            throw new IllegalArgumentException("表格字段缺少子表映射: " + request.tableField());
        }
        DynamicTableSql.requireTableName(subTableName);
        Map<String, FormFieldValidator.FieldDef> subDefMap = new LinkedHashMap<>();
        for (FormFieldValidator.FieldDef subDef : tableDef.subFields()) {
            subDefMap.put(subDef.name(), subDef);
        }

        List<String> setParts = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> entry : request.fields().entrySet()) {
            FormFieldValidator.FieldDef def = subDefMap.get(entry.getKey());
            if (def == null || "LABEL".equals(def.type())) {
                throw new IllegalArgumentException("回写列不存在或不允许回写: " + entry.getKey());
            }
            String colName = DynamicTableSql.requireColumn(def.name(), FieldType.valueOf(def.type()));
            setParts.add(DynamicTableSql.quote(colName) + " = ?");
            params.add(serializeValue(def, entry.getValue()));
        }
        setParts.add("\"update_time\" = ?");
        params.add(LocalDateTime.now());
        setParts.add("\"update_by\" = ?");
        params.add(request.actorId());
        setParts.add("\"version\" = \"version\" + 1");

        Long currentVersion = queryVersion(subTableName,
                "\"id\" = ? AND \"parent_record_id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?",
                request.rowId(), request.recordId(), request.tenantId());
        if (currentVersion == null) {
            // 来源行不存在/已删/不属于本记录：拒绝（错行防线）
            return new WritebackResult(WritebackResult.NOT_FOUND, null);
        }
        if (request.expectedRowVersion() != null
                && !request.expectedRowVersion().equals(currentVersion)) {
            // 派发后父侧该行已被编辑：版本冲突挂起，等待有权处置
            return new WritebackResult(WritebackResult.VERSION_CONFLICT, currentVersion);
        }

        StringBuilder where = new StringBuilder("\"id\" = ? AND \"parent_record_id\" = ?");
        params.add(request.rowId());
        params.add(request.recordId());
        if (request.expectedRowVersion() != null) {
            where.append(" AND \"version\" = ?");
            params.add(request.expectedRowVersion());
        }
        where.append(" AND \"deleted\" = 0 AND \"tenant_id\" = ?");
        params.add(request.tenantId());

        int affected = DynamicTableSql.update(jdbcTemplate, subTableName,
                "UPDATE " + DynamicTableSql.quote(subTableName) + " SET "
                        + String.join(", ", setParts) + " WHERE " + where,
                params.toArray());
        if (affected == 0) {
            // 版本守卫竞态：挂起（可诊断，不覆盖）
            return new WritebackResult(WritebackResult.VERSION_CONFLICT, currentVersion);
        }
        log.info("子表行回写完成: formKey={}, tableField={}, rowId={}, version {} -> {}",
                request.formKey(), request.tableField(), request.rowId(),
                currentVersion, currentVersion + 1);
        return new WritebackResult(WritebackResult.WRITTEN, currentVersion + 1);
    }

    // ==================== 工具 ====================

    private Long queryVersion(String tableName, String where, Object... params) {
        List<Object> bind = new ArrayList<>(List.of(params));
        String sql = "SELECT \"version\" FROM " + DynamicTableSql.quote(tableName)
                + " WHERE " + where;
        List<Map<String, Object>> rows = DynamicTableSql.query(jdbcTemplate, tableName,
                sql, bind.toArray());
        if (rows.isEmpty()) {
            return null;
        }
        Object version = rows.get(0).get("version");
        return version == null ? null : Long.valueOf(String.valueOf(version));
    }

    /** BOOL/多选列表值与提交路径同口径序列化。 */
    private Object serializeValue(FormFieldValidator.FieldDef def, Object value) {
        if (value == null) {
            return null;
        }
        if ("BOOL".equals(def.type())) {
            return FormFieldValidator.convertBoolValue(value);
        }
        boolean multiselectLike = "MULTISELECT".equals(def.type()) || "ATTACHMENT".equals(def.type())
                || "IMAGE".equals(def.type());
        boolean objectMulti = def.multiple() && ("USER".equals(def.type()) || "DEPT".equals(def.type()));
        if ((multiselectLike || objectMulti) && (value instanceof List<?> || value instanceof Map<?, ?>)) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException("回写值序列化失败: " + def.name(), e);
            }
        }
        return value;
    }

    private Map<String, String> parseSubTableMapping(String subTableMappingJson) {
        if (subTableMappingJson == null || subTableMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(subTableMappingJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((field, table) -> result.put(field, String.valueOf(table)));
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
