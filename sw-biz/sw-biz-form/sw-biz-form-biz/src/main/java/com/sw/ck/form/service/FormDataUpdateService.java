package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDataUpdateRequest;
import com.sw.ck.form.api.dto.SubTableRowAction;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.ColumnValidation;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 表单记录更新服务。
 * <p>
 * 对已发布表单的动态宽表执行单条记录的更新，含：
 * <ol>
 *   <li>记录存在性检查 + 乐观锁版本校验</li>
 *   <li>主表字段校验 + 整量 UPDATE（乐观锁）</li>
 *   <li>子表行按变动状态分流（ADD / UPDATE / DELETE / UNCHANGED），同一事务</li>
 * </ol>
 * </p>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>列名/表名过白名单（{@link ColumnValidation#physicalColumnName(String, FieldType)}）</li>
 *   <li>值一律 PreparedStatement ? 参数化绑定</li>
 *   <li>裸 SQL 手写 WHERE deleted = 0 AND tenant_id = ?（不吃拦截器）</li>
 *   <li>子表改/删必须带 parent_record_id 防越权动他人子行</li>
 *   <li>乐观锁命中 0 行区分：记录不存在（1507） vs 版本冲突（1508）</li>
 * </ul>
 */
@Service
public class FormDataUpdateService {

    private static final Logger log = LoggerFactory.getLogger(FormDataUpdateService.class);

    /** 表名校验正则（对齐 DynamicTableManager.generateTableName 的 assert 模式） */
    private static final String TABLE_NAME_PATTERN = "^sw_form(_table)?_[a-z][a-z0-9]{9}$";

    /** UUID v4 形态正则（防伪造 id 注入） */
    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final FormDefService formDefService;
    private final FormDefMapper formDefMapper;
    private final FormConfigMapper formConfigMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FormIdGenerator idGenerator;
    private final DictFacade dictFacade;
    private final FormFieldValidator formFieldValidator;

    public FormDataUpdateService(FormDefService formDefService,
                                  FormDefMapper formDefMapper,
                                  FormConfigMapper formConfigMapper,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper,
                                  FormIdGenerator idGenerator,
                                  DictFacade dictFacade,
                                  FormFieldValidator formFieldValidator) {
        this.formDefService = formDefService;
        this.formDefMapper = formDefMapper;
        this.formConfigMapper = formConfigMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.dictFacade = dictFacade;
        this.formFieldValidator = formFieldValidator;
    }

    // ==================== 主入口 ====================

    /**
     * 更新一条表单记录（主表整量 + 子表按变动状态分流 + 乐观锁）。
     * <p>
     * 事务边界：记录存在性检查 → 乐观锁校验 → 主表更新 → 子表分流，
     * 任一失败整事务回滚。
     * </p>
     *
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     * @param request  更新请求（主表数据 + version + 子表行变动）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRecord(String formKey, String recordId, FormDataUpdateRequest request) {
        // —— Step 1: 获取当前用户 ——
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        Long tenantId = loginUser.getTenantId();
        Long userId = loginUser.getUserId();

        log.info("Form update start: formKey={}, recordId={}, userId={}, tenantId={}",
                formKey, recordId, userId, tenantId);

        // —— Step 2: 解析 formKey → FormDefEntity ——
        LambdaQueryWrapper<FormDefEntity> defQuery = Wrappers.lambdaQuery(FormDefEntity.class)
                .eq(FormDefEntity::getFormKey, formKey);
        FormDefEntity formDef = formDefMapper.selectOne(defQuery);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        if (!formDefService.isCurrentUserVisible(formKey)) {
            throw new BaseException(FormErrorCode.FORM_NOT_FOUND, "表单 '" + formKey + "' 不存在");
        }
        if (!"PUBLISHED".equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.FORM_NOT_PUBLISHED, "表单 '" + formKey + "' 未发布，不能更新");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "表单 '" + formKey + "' 无物理表，无法更新");
        }
        validateTableName(tableName);

        // —— Step 3: 加载字段定义 ——
        Map<String, Object> submittedData = request.getData();
        if (submittedData == null) {
            submittedData = Map.of();
        }
        Map<String, FormFieldValidator.FieldDef> fieldDefs =
                formFieldValidator.loadAndParseFieldDefs(formDef.getId(), submittedData);

        // —— Step 4: 查主记录存在且未删（同时取 version 做乐观锁校验） ——
        Long storedVersion = checkRecordExistsAndGetVersion(tableName, recordId, tenantId);

        // —— Step 5: 乐观锁校验 ——
        Long requestVersion = request.getVersion();
        if (requestVersion == null) {
            throw new BaseException(FormErrorCode.VERSION_CONFLICT, "缺少 version 字段，无法更新（乐观锁）");
        }
        if (!requestVersion.equals(storedVersion)) {
            throw new BaseException(FormErrorCode.VERSION_CONFLICT,
                    "数据版本冲突：当前版本 " + storedVersion + "，请求版本 " + requestVersion + "，请刷新后重试");
        }

        // —— Step 6: 主表字段校验（复用量产校验口径 1400-1404） ——
        formFieldValidator.validateFields(fieldDefs, submittedData, dictFacade);

        // —— Step 7: 主表整量 UPDATE ——
        int affected = updateMainRecord(tableName, fieldDefs, submittedData,
                recordId, requestVersion, tenantId, userId);
        if (affected == 0) {
            // 检查之间记录可能被删
            Long currentVersion = checkRecordExistsAndGetVersion(tableName, recordId, tenantId);
            if (currentVersion == null) {
                throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "记录已被删除");
            }
            throw new BaseException(FormErrorCode.VERSION_CONFLICT,
                    "数据版本冲突：更新时版本已变化，请刷新后重试");
        }

        log.debug("Updated main record: table={}, recordId={}, oldVersion={}", tableName, recordId, requestVersion);

        // —— Step 8: 子表行分流 ——
        Map<String, String> subTableMapping = parseSubTableMapping(formDef.getSubTableMapping());
        Map<String, List<SubTableRowAction>> subTableRows = request.getSubTableRows();
        if (subTableRows != null && !subTableRows.isEmpty() && !subTableMapping.isEmpty()) {
            processSubTableRows(tableName, recordId, fieldDefs, subTableMapping,
                    subTableRows, tenantId, userId);
        }

        log.info("Form update completed: formKey={}, recordId={}", formKey, recordId);
    }

    // ==================== Step 4: 记录存在性检查 + 版本获取 ====================

    /**
     * 查主记录是否存在（未删），返回当前 version。
     *
     * @return 当前版本号；记录不存在返回 null
     */
    private Long checkRecordExistsAndGetVersion(String tableName, String recordId, Long tenantId) {
        String sql = "SELECT \"version\" FROM \"" + tableName
                + "\" WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?";
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, recordId, tenantId);
        } catch (Exception e) {
            log.error("Record existence check failed: table={}, recordId={}", tableName, recordId, e);
            throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "查询记录失败: " + e.getMessage());
        }

        if (rows == null || rows.isEmpty()) {
            throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        }

        Object versionObj = rows.get(0).get("version");
        if (versionObj instanceof Number n) {
            return n.longValue();
        }
        // 防御：version 列为 null 时视为 0
        return 0L;
    }

    // ==================== Step 7: 主表整量 UPDATE ====================

    /**
     * 构建并执行主表整量 UPDATE（乐观锁）。
     * <p>
     * UPDATE 所有用户列 + 审计列，WHERE 带 id + version + deleted + tenant_id。
     * 成功则 version 自增 1。
     * </p>
     *
     * @return affected rows
     */
    private int updateMainRecord(String tableName,
                                  Map<String, FormFieldValidator.FieldDef> fieldDefs,
                                  Map<String, Object> submittedData,
                                  String recordId,
                                  Long currentVersion,
                                  Long tenantId,
                                  Long userId) {
        // 构建 SET 子句：用户列 + 审计列
        List<String> setParts = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (FormFieldValidator.FieldDef def : fieldDefs.values()) {
            if ("TABLE".equals(def.type())) continue; // TABLE 不在主表加列
            if ("LABEL".equals(def.type())) continue; // v0.0.2：说明文字非输入字段，无列

            String colName = ColumnValidation.physicalColumnName(def.name(), FieldType.valueOf(def.type()));
            Object value = submittedData.get(def.name());

            if ("BOOL".equals(def.type())) {
                value = FormFieldValidator.convertBoolValue(value);
            }

            setParts.add("\"" + colName + "\" = ?");
            params.add(value);
        }

        // 审计列
        setParts.add("\"update_time\" = ?");
        params.add(LocalDateTime.now());
        setParts.add("\"update_by\" = ?");
        params.add(userId);
        // version 自增
        setParts.add("\"version\" = \"version\" + 1");

        // WHERE 条件
        params.add(recordId);
        params.add(currentVersion);
        params.add(tenantId);

        String sql = "UPDATE \"" + tableName + "\" SET " + String.join(", ", setParts)
                + " WHERE \"id\" = ? AND \"version\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?";

        log.debug("Update SQL: {}", sql);
        return jdbcTemplate.update(sql, params.toArray());
    }

    // ==================== Step 8: 子表行分流 ====================

    /**
     * 按变动状态分流处理子表行（同一事务）。
     *
     * @param mainTableName    主表物理名（用于日志）
     * @param recordId         主记录 UUID
     * @param mainFieldDefs    主表字段定义（含 TABLE 字段的 subFields）
     * @param subTableMapping  字段名 → 子表物理名映射
     * @param subTableRows     前端传来的子表变动 {字段名 → [行变动]}
     * @param tenantId         当前租户
     * @param userId           当前用户
     */
    private void processSubTableRows(String mainTableName,
                                      String recordId,
                                      Map<String, FormFieldValidator.FieldDef> mainFieldDefs,
                                      Map<String, String> subTableMapping,
                                      Map<String, List<SubTableRowAction>> subTableRows,
                                      Long tenantId,
                                      Long userId) {
        for (Map.Entry<String, List<SubTableRowAction>> entry : subTableRows.entrySet()) {
            String tableFieldName = entry.getKey();
            List<SubTableRowAction> rows = entry.getValue();

            if (rows == null || rows.isEmpty()) {
                continue;
            }

            // 解析子表名
            String subTableName = subTableMapping.get(tableFieldName);
            if (subTableName == null || subTableName.isBlank()) {
                log.warn("No sub-table mapping for TABLE field '{}', skipping", tableFieldName);
                continue;
            }

            // 防御性表名校验
            try {
                validateTableName(subTableName);
            } catch (BaseException e) {
                log.warn("Invalid sub-table name '{}' in subTableMapping, skip", subTableName);
                continue;
            }

            // 获取子表字段定义
            FormFieldValidator.FieldDef tableFieldDef = mainFieldDefs.get(tableFieldName);
            List<FormFieldValidator.FieldDef> subFieldDefs = (tableFieldDef != null && tableFieldDef.subFields() != null)
                    ? tableFieldDef.subFields() : List.of();

            // 构建子表字段名→定义映射（用于校验）
            Map<String, FormFieldValidator.FieldDef> subDefMap = new LinkedHashMap<>();
            for (FormFieldValidator.FieldDef subDef : subFieldDefs) {
                subDefMap.put(subDef.name(), subDef);
            }

            for (SubTableRowAction row : rows) {
                String action = row.getAction();
                if (action == null || action.isBlank()) {
                    log.warn("Sub-table row missing action, skipping");
                    continue;
                }

                switch (action.toUpperCase()) {
                    case "ADD" -> processSubRowAdd(subTableName, recordId, subFieldDefs, subDefMap,
                            row, tenantId, userId);
                    case "UPDATE" -> processSubRowUpdate(subTableName, recordId, subFieldDefs, subDefMap,
                            row, tenantId, userId);
                    case "DELETE" -> processSubRowDelete(subTableName, recordId, row, tenantId);
                    case "UNCHANGED" -> { /* 跳过 */ }
                    default -> log.warn("Unknown sub-table row action '{}', skipping", action);
                }
            }
        }
    }

    // —— ADD ——

    private void processSubRowAdd(String subTableName, String recordId,
                                   List<FormFieldValidator.FieldDef> subFieldDefs,
                                   Map<String, FormFieldValidator.FieldDef> subDefMap,
                                   SubTableRowAction row, Long tenantId, Long userId) {
        Map<String, Object> rowData = row.getData();
        if (rowData == null) {
            rowData = Map.of();
        }

        // 校验子表行字段
        formFieldValidator.validateFields(subDefMap, rowData, dictFacade);

        // 构建 INSERT
        String subRecordId = idGenerator.generate();
        Map<String, Object> sysCols = new LinkedHashMap<>();
        sysCols.put("id", subRecordId);
        sysCols.put("tenant_id", tenantId);
        sysCols.put("deleted", 0);
        sysCols.put("create_time", LocalDateTime.now());
        sysCols.put("create_by", userId);
        sysCols.put("update_time", LocalDateTime.now());
        sysCols.put("update_by", userId);
        sysCols.put("version", 0L);
        sysCols.put("parent_record_id", recordId);

        List<String> columns = new ArrayList<>(sysCols.keySet());
        List<Object> values = new ArrayList<>(sysCols.values());

        for (FormFieldValidator.FieldDef subDef : subFieldDefs) {
            if ("LABEL".equals(subDef.type())) continue; // v0.0.2：说明文字非输入字段，无列
            String colName = ColumnValidation.physicalColumnName(subDef.name(), FieldType.valueOf(subDef.type()));
            Object val = rowData.get(subDef.name());
            if ("BOOL".equals(subDef.type())) {
                val = FormFieldValidator.convertBoolValue(val);
            }
            columns.add(colName);
            values.add(val);
        }

        String quotedCols = columns.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO \"" + subTableName + "\" (" + quotedCols + ") VALUES (" + placeholders + ")";

        jdbcTemplate.update(sql, values.toArray());
        log.debug("Added sub-table row: table={}, rowId={}, parentRecordId={}", subTableName, subRecordId, recordId);
    }

    // —— UPDATE ——

    private void processSubRowUpdate(String subTableName, String recordId,
                                      List<FormFieldValidator.FieldDef> subFieldDefs,
                                      Map<String, FormFieldValidator.FieldDef> subDefMap,
                                      SubTableRowAction row, Long tenantId, Long userId) {
        String rowId = row.getId();
        if (rowId == null || rowId.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_REQUIRED, "UPDATE 子表行缺少 id");
        }
        validateUuidFormat(rowId);

        Map<String, Object> rowData = row.getData();
        if (rowData == null) {
            rowData = Map.of();
        }

        // 校验子表行字段
        formFieldValidator.validateFields(subDefMap, rowData, dictFacade);

        // 构建 UPDATE
        List<String> setParts = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (FormFieldValidator.FieldDef subDef : subFieldDefs) {
            if ("LABEL".equals(subDef.type())) continue; // v0.0.2：说明文字非输入字段，无列
            String colName = ColumnValidation.physicalColumnName(subDef.name(), FieldType.valueOf(subDef.type()));
            Object val = rowData.get(subDef.name());
            if ("BOOL".equals(subDef.type())) {
                val = FormFieldValidator.convertBoolValue(val);
            }
            setParts.add("\"" + colName + "\" = ?");
            params.add(val);
        }

        setParts.add("\"update_time\" = ?");
        params.add(LocalDateTime.now());
        setParts.add("\"update_by\" = ?");
        params.add(userId);

        // WHERE：id + parent_record_id + deleted + tenant_id（防越权）
        params.add(rowId);
        params.add(recordId);
        params.add(tenantId);

        String sql = "UPDATE \"" + subTableName + "\" SET " + String.join(", ", setParts)
                + " WHERE \"id\" = ? AND \"parent_record_id\" = ?"
                + " AND \"deleted\" = 0 AND \"tenant_id\" = ?";

        int affected = jdbcTemplate.update(sql, params.toArray());
        if (affected == 0) {
            log.warn("Sub-table UPDATE affected 0 rows: table={}, rowId={}, parentRecordId={}",
                    subTableName, rowId, recordId);
            // 行可能不存在/已删/不属于本记录，不抛错（幂等容忍）
        } else {
            log.debug("Updated sub-table row: table={}, rowId={}", subTableName, rowId);
        }
    }

    // —— DELETE ——

    private void processSubRowDelete(String subTableName, String recordId,
                                      SubTableRowAction row, Long tenantId) {
        String rowId = row.getId();
        if (rowId == null || rowId.isBlank()) {
            throw new BaseException(FormErrorCode.SUBMIT_FIELD_REQUIRED, "DELETE 子表行缺少 id");
        }
        validateUuidFormat(rowId);

        String sql = "UPDATE \"" + subTableName
                + "\" SET \"deleted\" = 1 WHERE \"id\" = ? AND \"parent_record_id\" = ?"
                + " AND \"deleted\" = 0 AND \"tenant_id\" = ?";

        int affected = jdbcTemplate.update(sql, rowId, recordId, tenantId);
        if (affected == 0) {
            log.debug("Sub-table DELETE affected 0 rows (already deleted or not found): table={}, rowId={}",
                    subTableName, rowId);
        } else {
            log.debug("Soft-deleted sub-table row: table={}, rowId={}", subTableName, rowId);
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 校验字符串是否为合法 UUID v4 形态（防伪造 id 注入）。
     */
    private void validateUuidFormat(String id) {
        if (!id.matches(UUID_PATTERN)) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED, "非法的记录 ID 格式: " + id);
        }
    }

    /**
     * 防御性表名校验。
     */
    private void validateTableName(String tableName) {
        if (!tableName.matches(TABLE_NAME_PATTERN)) {
            log.error("Table name '{}' does not match expected pattern '{}'", tableName, TABLE_NAME_PATTERN);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表名格式异常");
        }
    }

    /**
     * 解析子表映射 JSON。
     */
    private Map<String, String> parseSubTableMapping(String subTableMappingJson) {
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
}
