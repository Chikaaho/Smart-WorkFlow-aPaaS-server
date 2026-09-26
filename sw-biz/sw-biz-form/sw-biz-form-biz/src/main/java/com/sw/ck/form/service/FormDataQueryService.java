package com.sw.ck.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FilterOp;
import com.sw.ck.form.api.dto.FormDataFilter;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.service.FormFieldEnrichmentService;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 表单数据查询服务。
 *
 * <p>对已发布表单的动态宽表执行条件分页查询。
 * 全部 SQL 经 {@link DynamicTableSql} 受控入口构造与执行：
 * 表名/列名在构造边界校验，{@code "deleted" = 0 AND "tenant_id" = ?} 由入口强制，
 * 值一律 {@code ?} 绑定，缺失即拒绝执行。</p>
 *
 * <h3>红线</h3>
 * <ul>
 *   <li>列名/表名过白名单（{@link DynamicTableSql#requireColumn(String, FieldType)}）</li>
 *   <li>值一律 PreparedStatement ? 参数化绑定</li>
 *   <li>绝不 SELECT *，显式枚举列</li>
 *   <li>绝不复用 IPage（拦截器对裸 JdbcTemplate 失效）</li>
 * </ul>
 */
@Service
public class FormDataQueryService {

    private static final Logger log = LoggerFactory.getLogger(FormDataQueryService.class);

    /** 分页硬上限 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 默认分页大小（对齐 PageParam 默认值） */
    private static final int DEFAULT_PAGE_SIZE = 10;

    // ==================== op × type 合法矩阵 ====================

    private static final Map<FieldType, Set<FilterOp>> ALLOWED_OPS = Map.of(
            FieldType.TEXT, Set.of(FilterOp.EQ, FilterOp.LIKE),
            FieldType.NUMBER, Set.of(FilterOp.EQ, FilterOp.GE, FilterOp.LE),
            FieldType.DATE, Set.of(FilterOp.EQ, FilterOp.GE, FilterOp.LE),
            FieldType.BOOL, Set.of(FilterOp.EQ),
            FieldType.DICT, Set.of(FilterOp.EQ),
            FieldType.REFERENCE, Set.of(FilterOp.EQ)
    );

    // ==================== 非可筛选类型 ====================

    private static final Set<FieldType> NON_FILTERABLE_TYPES = Set.of(
            FieldType.TABLE, FieldType.RICH_TEXT
    );

    // ==================== 依赖 ====================

    private final FormDefService formDefService;
    private final FormDefMapper formDefMapper;
    private final FormConfigMapper formConfigMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FormDataQueryService(FormDefService formDefService,
                                FormDefMapper formDefMapper,
                                FormConfigMapper formConfigMapper,
                                JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper) {
        this.formDefService = formDefService;
        this.formDefMapper = formDefMapper;
        this.formConfigMapper = formConfigMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ==== I2 依赖（可选注入；既有测试构造不受影响；缺省时按登录态降级解析） ====

    private FieldPermissionService fieldPermissionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFieldPermissionService(FieldPermissionService fieldPermissionService) {
        this.fieldPermissionService = fieldPermissionService;
    }

    private com.sw.ck.common.security.LoginContextProvider loginContextProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLoginContextProvider(com.sw.ck.common.security.LoginContextProvider loginContextProvider) {
        this.loginContextProvider = loginContextProvider;
    }

    private com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDeptScopeProvider(com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
        this.deptScopeProvider = deptScopeProvider;
    }

    // ==================== 主入口 ====================

    /**
     * 查询表单数据（分页 + 过滤）。
     *
     * @param formKey 表单业务标识
     * @param request 查询请求（分页 + 过滤条件）
     * @return 分页结果
     */
    /**
     * 查询表单数据（分页 + 过滤），附带数据范围过滤（导出等有界消费方使用）。
     * <p>scopeFilter 为 none 时不追加条件；否则对 create_by 追加：
     * SELF → 等值；DEPT 家族 → create_by IN (SELECT id FROM sys_user WHERE dept_id IN (...))；
     * 空部门集 → 恒假（1 = 0）。</p>
     */
    public PageResult<Map<String, Object>> queryFormData(String formKey, FormDataQueryRequest request,
                                                         DataScopeFilter scopeFilter) {
        return doQueryFormData(formKey, request, scopeFilter, MAX_PAGE_SIZE);
    }

    /**
     * 查询表单数据（分页 + 过滤），并允许调用方指定更大的单页上限（导出等有界消费方）。
     * <p>maxPageSize 仍为硬上限：请求超过该值时被钳制到该值。</p>
     */
    public PageResult<Map<String, Object>> queryFormData(String formKey, FormDataQueryRequest request,
                                                         DataScopeFilter scopeFilter, int maxPageSize) {
        return doQueryFormData(formKey, request, scopeFilter, maxPageSize);
    }

    /**
     * 查询表单数据（分页 + 过滤），不做额外数据范围过滤（保持既有语义，租户边界恒生效）。
     */
    public PageResult<Map<String, Object>> queryFormData(String formKey, FormDataQueryRequest request) {
        return doQueryFormData(formKey, request, null, MAX_PAGE_SIZE);
    }

    private PageResult<Map<String, Object>> doQueryFormData(String formKey, FormDataQueryRequest request,
                                                            DataScopeFilter scopeFilter, int maxPageSize) {
        // —— Step 1: 获取当前用户 ——
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        Long tenantId = loginUser.getTenantId();

        // —— Step 2: 解析 formKey → FormDefDTO（含 status + physicalTableName） ——
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (!formDefService.isCurrentUserVisible(formKey)) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (!"PUBLISHED".equals(formDef.getStatus()) && !"DISABLED".equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 未发布，不能查询");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST,
                    "该表单尚未完成数据表初始化，请联系管理员处理");
        }

        // —— Step 2.5: 表名防御性校验 ——
        validateTableName(tableName);

        // —— Step 3: 加载 definition JSON 并解析字段类型 ——
        Map<String, FieldType> fieldTypeMap = loadFieldTypeMap(formDef.getId());

        // —— I2: 当前身份无 view 权的字段（投影剔除 + 筛选拒绝，敏感值不可侧漏） ——
        Set<String> viewDenied = viewDeniedFields(loginUser, formDef.getId());

        // —— Step 4: 校验过滤条件 ——
        List<FilterClause> clauses = validateAndBuildClauses(request.getFilters(), fieldTypeMap, viewDenied,
                fieldDisplayFor(formDef.getId()));

        // —— Step 5: 构建列投影（I2：无 view 权字段不出响应） ——
        List<String> projectionColumns = buildProjection(fieldTypeMap, viewDenied);

        // —— Step 6: 钳制分页参数 ——
        int page = Math.max(1, (int) request.getPageNum());
        int size = clampSize((int) request.getPageSize(), maxPageSize);
        int offset = (page - 1) * size;

        // —— Step 7: 构建 WHERE 子句 ——
        StringBuilder whereBuilder = new StringBuilder();
        List<Object> filterParams = new ArrayList<>();

        whereBuilder.append("\"deleted\" = 0");
        whereBuilder.append(" AND \"tenant_id\" = ?");
        filterParams.add(tenantId);

        // —— 数据范围过滤（I2：服务端权威强制，调用方不可选择关闭；create_by 归属语义） ——
        DataScopeFilter effectiveScope = scopeFilter != null
                ? scopeFilter
                : FormDataScopeSupport.resolve(loginUser, loginContextProvider, deptScopeProvider);
        FormDataScopeSupport.appendWhere(whereBuilder, filterParams, effectiveScope);

        for (FilterClause clause : clauses) {
            whereBuilder.append(" AND ").append(clause.sql());
            filterParams.add(clause.value());
        }

        String whereSql = whereBuilder.toString();

        // —— Step 8: COUNT 查询 ——
        String countSql = "SELECT COUNT(*) FROM " + DynamicTableSql.quote(tableName) + " WHERE " + whereSql;
        Long total;
        try {
            total = DynamicTableSql.queryForLong(jdbcTemplate, tableName, countSql, filterParams.toArray());
        } catch (Exception e) {
            log.error("Count query failed: table={}, sql={}", tableName, countSql, e);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "查询记录时系统未能完成，请稍后重试");
        }
        if (total == null) total = 0L;

        // —— Step 9: 数据查询 ——
        String columns = projectionColumns.stream()
                .map(DynamicTableSql::quote)
                .collect(Collectors.joining(", "));
        String dataSql = "SELECT " + columns + " FROM " + DynamicTableSql.quote(tableName) + " WHERE " + whereSql
                + " ORDER BY \"create_time\" DESC LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(filterParams);
        dataParams.add((long) size);
        dataParams.add((long) offset);

        List<Map<String, Object>> records;
        try {
            records = DynamicTableSql.query(jdbcTemplate, tableName, dataSql, dataParams.toArray());
        } catch (Exception e) {
            log.error("Data query failed: table={}, sql={}", tableName, dataSql, e);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "查询记录时系统未能完成，请稍后重试");
        }

        // —— Step 10: 构建 PageResult ——
        PageResult<Map<String, Object>> result = new PageResult<>();
        result.setRecords(records != null ? records : List.of());
        result.setTotal(total);
        result.setPageNum(page);
        result.setPageSize(size);
        return result;
    }

    // ==================== 单条记录详情查询 ====================

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
    public Map<String, Object> getRecordDetail(String formKey, String recordId) {
        // —— Step 1: 获取当前用户 ——
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        Long tenantId = loginUser.getTenantId();

        // —— Step 2: 解析 formKey → FormDefDTO ——
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (!"PUBLISHED".equals(formDef.getStatus()) && !"DISABLED".equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 未发布，不能查询");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST,
                    "该表单尚未完成数据表初始化，请联系管理员处理");
        }
        validateTableName(tableName);

        // —— Step 3: 加载字段元数据 ——
        Map<String, FieldType> fieldTypeMap = loadFieldTypeMap(formDef.getId());
        Map<String, List<SubFieldMeta>> tableSubFields = loadTableSubFields(formDef.getId());

        // —— I2: 字段查看权限 + 记录数据范围（服务端权威强制） ——
        Set<String> viewDenied = viewDeniedFields(loginUser, formDef.getId());

        // —— Step 4: 构建详情投影（含 version；剔除无 view 权字段） ——
        List<String> projectionColumns = buildDetailProjection(fieldTypeMap, viewDenied);

        // —— Step 5: 查询主记录（数据范围条件与列表同口径强制） ——
        String columns = projectionColumns.stream()
                .map(DynamicTableSql::quote)
                .collect(Collectors.joining(", "));
        StringBuilder detailWhere = new StringBuilder("\"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?");
        List<Object> detailParams = new ArrayList<>(List.of(recordId, tenantId));
        DataScopeFilter detailScope = FormDataScopeSupport.resolve(loginUser, loginContextProvider, deptScopeProvider);
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

        if (records == null || records.isEmpty()) {
            throw new BaseException(FormErrorCode.RECORD_NOT_FOUND, "记录不存在或已删除");
        }

        Map<String, Object> result = new LinkedHashMap<>(records.get(0));

        // —— Step 6: 加载子表行 ——
        FormDefEntity formDefEntity = formDefMapper.selectById(formDef.getId());
        if (formDefEntity != null) {
            Map<String, String> subTableMapping = parseSubTableMapping(formDefEntity.getSubTableMapping());

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
                List<String> subProjection = buildSubTableProjection(subFields).stream()
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

    /**
     * 以与详情查询相同的租户、删除和表单可见性边界检查记录是否存在。
     * <p>
     * 该方法供跨模块 REFERENCE 契约校验使用；只查询常量列，不读取业务数据，
     * 记录 ID 作为参数绑定，物理表名仍经过固定白名单校验。
     * </p>
     */
    public boolean canCurrentUserAccessRecord(String formKey, String recordId) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getTenantId() == null
                || recordId == null || recordId.isBlank()) {
            return false;
        }
        FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null || !"PUBLISHED".equals(formDef.getStatus())
                || !formDefService.isCurrentUserVisible(formKey)) {
            return false;
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || !DynamicTableSql.isValidTableName(tableName)) {
            return false;
        }
        String sql = "SELECT 1 AS present FROM " + DynamicTableSql.quote(tableName)
                + " WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ? LIMIT 1";
        try {
            List<Map<String, Object>> rows = DynamicTableSql.query(jdbcTemplate, tableName, sql,
                    recordId, loginUser.getTenantId());
            return !rows.isEmpty();
        } catch (Exception e) {
            log.warn("REFERENCE record access check failed: formKey={}, recordId={}", formKey, recordId, e);
            return false;
        }
    }

    // ==================== 表名校验 ====================

    /**
     * 防御性表名校验（表名来自注册表，发布期已校验，此处为纵深防御）。
     * <p>与 {@link DynamicTableSql} 共用同一正则常量；受控入口在执行前会再校验一次。</p>
     */
    private void validateTableName(String tableName) {
        if (!DynamicTableSql.isValidTableName(tableName)) {
            log.error("Table name '{}' does not match expected pattern '{}'",
                    tableName, DynamicTableSql.TABLE_NAME_PATTERN);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "该表单的数据表配置异常，请联系管理员处理");
        }
    }

    // ==================== 字段类型解析 ====================

    /**
     * 从 sw_form_config.definition JSON 解析字段名 → FieldType 映射。
     */
    private Map<String, FieldType> loadFieldTypeMap(String formId) {
        // 查主表 definition（parent_table IS NULL）
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormConfigEntity>()
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
                String typeStr = fieldNode.has("type") ? fieldNode.get("type").asText() : "TEXT";

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

    // ==================== 过滤条件校验与构建 ====================

    /**
     * 校验过滤条件并构建 SQL 子句。
     * <p>
     * 对每个 filter 执行：
     * <ol>
     *   <li>字段名是否在 definition 中</li>
     *   <li>op 是否为 IN（v1 不支持）</li>
     *   <li>字段类型是否可筛选</li>
     *   <li>op 是否在该类型的合法集合中</li>
     * </ol>
     * 全部通过后将逻辑字段名转为物理列名，生成 SQL 片段与参数值。
     * </p>
     */
    private List<FilterClause> validateAndBuildClauses(List<FormDataFilter> filters,
                                                        Map<String, FieldType> fieldTypeMap,
                                                        Set<String> viewDenied,
                                                        Map<String, String> fieldDisplay) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }

        List<FilterClause> clauses = new ArrayList<>();

        for (FormDataFilter filter : filters) {
            String field = filter.getField();
            FilterOp op = filter.getOp();
            Object value = filter.getValue();

            // —— 字段名非空 ——
            if (field == null || field.isBlank()) {
                throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_UNKNOWN, "过滤字段名为空");
            }

            // —— op 非空 ——
            if (op == null) {
                throw new BaseException(FormErrorCode.QUERY_FILTER_OP_NOT_SUPPORTED, "过滤操作符为空");
            }

            // —— v1 不支持 IN ——
            if (op == FilterOp.IN) {
                throw new BaseException(FormErrorCode.QUERY_FILTER_OP_NOT_SUPPORTED,
                        "过滤操作符 IN 在 v1 暂不支持");
            }

            // —— 系统主键 id：仅放行 EQ（引用显示名解析等单记录定位） ——
            // id 是 SYSTEM_COLUMNS 固定主键列，不经 definition 校验；记录数据范围
            // 条件仍强制并入 WHERE，跨范围按 id 过滤查不到即 fail-closed，无侧漏。
            if ("id".equals(field)) {
                if (op != FilterOp.EQ) {
                    throw new BaseException(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH,
                            "系统列 'id' 仅支持 EQ 过滤");
                }
                if (value == null || (value instanceof String s && s.isBlank())) {
                    throw new BaseException(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH,
                            "过滤字段 'id' 的值为空");
                }
                clauses.add(buildClause("id", op, value, FieldType.TEXT));
                continue;
            }

            // —— 字段是否在 definition 中 ——
            FieldType fieldType = fieldTypeMap.get(field);
            if (fieldType == null || viewDenied.contains(field)) {
                // 无 view 权字段与未知字段同口径拒绝，不确认其存在性
                // 未知字段与无 view 权字段同口径：回显用户自己的输入串，并作为目录参数传出
                throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_UNKNOWN,
                        new Object[]{field}, "过滤字段 '" + field + "' 不在表单定义中");
            }

            // —— 字段类型是否可筛选 ——
            if (NON_FILTERABLE_TYPES.contains(fieldType) || !fieldType.isEnabled()) {
                // 目录条目带 {0}/{1}：用户看到的必须是字段显示名，不是内部 key
                throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE,
                        new Object[]{fieldDisplay.getOrDefault(field, field), fieldType},
                        "字段「" + fieldDisplay.getOrDefault(field, field) + "」（类型 " + fieldType + "）不支持筛选");
            }

            // —— op 是否在该类型的合法集合中 ——
            Set<FilterOp> allowed = ALLOWED_OPS.get(fieldType);
            if (allowed == null || !allowed.contains(op)) {
                throw new BaseException(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH,
                        new Object[]{fieldDisplay.getOrDefault(field, field)},
                        "操作符 " + op + " 不适用于字段「" + fieldDisplay.getOrDefault(field, field) + "」");
            }

            // —— 值非空（空值过滤无意义） ——
            if (value == null || (value instanceof String s && s.isBlank())) {
                throw new BaseException(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH,
                        "过滤字段 '" + field + "' 的值为空");
            }

            // —— 物理列名（唯一出口）；LABEL 非输入字段无可查询列 ——
            if (fieldType == com.sw.ck.form.dynamic.FieldType.LABEL) {
                throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE,
                        new Object[]{fieldDisplay.getOrDefault(field, field), fieldType},
                        "说明文字字段「" + fieldDisplay.getOrDefault(field, field) + "」不支持筛选");
            }
            String physicalCol = DynamicTableSql.requireColumn(field, fieldType);

            // —— 构建 SQL 子句 ——
            clauses.add(buildClause(physicalCol, op, value, fieldType));
        }

        return clauses;
    }

    /**
     * 构建单个过滤 SQL 子句与参数值。
     */
    private FilterClause buildClause(String colName, FilterOp op, Object value, FieldType fieldType) {
        String sql;
        Object paramValue;

        switch (op) {
            case EQ -> {
                sql = "\"" + colName + "\" = ?";
                paramValue = convertFilterValue(value, fieldType);
            }
            case LIKE -> {
                sql = "\"" + colName + "\" LIKE ? ESCAPE '\\'";
                paramValue = escapeLike(value.toString());
            }
            case GE -> {
                sql = "\"" + colName + "\" >= ?";
                paramValue = convertFilterValue(value, fieldType);
            }
            case LE -> {
                sql = "\"" + colName + "\" <= ?";
                paramValue = convertFilterValue(value, fieldType);
            }
            default -> throw new BaseException(FormErrorCode.QUERY_FILTER_OP_NOT_SUPPORTED,
                    "不支持的操作符: " + op);
        }

        return new FilterClause(sql, paramValue);
    }

    /**
     * 转换过滤值为数据库可比类型。
     * <p>
     * BOOL：true/false/1/0 → Integer 1/0（对齐 SMALLINT 存储）。
     * 其他类型原样返回，由 JDBC 驱动处理。
     * </p>
     */
    private Object convertFilterValue(Object value, FieldType fieldType) {
        if (fieldType == FieldType.BOOL) {
            if (value instanceof Boolean b) return b ? 1 : 0;
            if (value instanceof Number n) return n.intValue() != 0 ? 1 : 0;
            if (value instanceof String s) {
                return switch (s.trim().toLowerCase()) {
                    case "true", "1", "yes", "on" -> 1;
                    case "false", "0", "no", "off", "" -> 0;
                    default -> throw new BaseException(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH,
                            "无法将 '" + s + "' 转换为布尔值");
                };
            }
            throw new BaseException(FormErrorCode.QUERY_FILTER_OP_TYPE_MISMATCH,
                    "无法将 " + value.getClass().getSimpleName() + " 转换为布尔值");
        }
        return value;
    }

    /**
     * LIKE 值转义：转义 \% \_ \\，并包裹 %value% 做包含匹配。
     * <p>
     * ESCAPE '\' 对齐 PostgreSQL / H2 行为。
     * </p>
     */
    static String escapeLike(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    // ==================== 列投影 ====================

    /**
     * 构建 SELECT 列投影。
     * <p>
     * 列集 = definition 中 type≠TABLE 的字段物理列（过白名单）
     * + REFERENCE 的 ref_{name}_id + 系统列。
     * </p>
     */
    /**
     * 投影列集合：系统列中剔除列表视图不需要的 deleted、tenant_id、version。
     * <p>
     * deleted 恒 0 对用户无意义；tenant_id 恒当前租户（噪音且轻微泄漏）；
     * version 是乐观锁、编辑期关注，列表不关心。
     * 注意：只剔除 SELECT 投影，WHERE 过滤照常保留 deleted=0 AND tenant_id=?。
     * </p>
     */
    private static final List<String> PROJECTION_SYSTEM_COLUMNS = List.of(
            "id", "create_time", "create_by", "update_time", "update_by"
    );

    /**
     * 构建 SELECT 列投影。
     * <p>
     * 列集 = definition 中 type≠TABLE 的字段物理列（过白名单）
     * + REFERENCE 的 ref_{name}_id + 投影系统列。
     * 列表视图排除 deleted、tenant_id、version（无业务意义）。
     * </p>
     */
    private List<String> buildProjection(Map<String, FieldType> fieldTypeMap, Set<String> viewDenied) {
        List<String> columns = new ArrayList<>(PROJECTION_SYSTEM_COLUMNS);

        for (Map.Entry<String, FieldType> entry : fieldTypeMap.entrySet()) {
            FieldType ft = entry.getValue();

            // 跳过不可投影类型
            if (ft == FieldType.TABLE) continue;
            if (ft == FieldType.LABEL) continue;
            if (!ft.isEnabled()) continue;
            // I2：无 view 权字段不进 SELECT 投影
            if (viewDenied.contains(entry.getKey())) continue;

            String physicalCol = DynamicTableSql.requireColumn(entry.getKey(), ft);
            columns.add(physicalCol);
        }

        return columns;
    }

    // ==================== 分页参数钳制 ====================

    private int clampSize(int size, int maxPageSize) {
        int cap = Math.max(1, maxPageSize);
        if (size < 1) return Math.min(DEFAULT_PAGE_SIZE, cap);
        return Math.min(size, cap);
    }

    // ==================== 详情查询辅助 ====================

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

    /**
     * I2：解析当前身份在指定表单上的无 view 权字段集合
     * （含 TABLE 子字段，键形如 {@code items.qty}；权限服务缺失时视为不设限）。
     */
    /** 字段键 → 显示名（R3a）：筛选/操作符拒绝消息向用户展示 label 而非内部键。 */
    private Map<String, String> fieldDisplayFor(String formId) {
        try {
            JsonNode root = objectMapper.readTree(loadDefinitionJsonForPermissions(formId));
            JsonNode fields = root == null ? null : root.get("fields");
            return FormFieldEnrichmentService.collectFieldLabels(fields);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Set<String> viewDeniedFields(LoginUser loginUser, String formId) {
        if (fieldPermissionService == null) {
            return Set.of();
        }
        String definitionJson = loadDefinitionJsonForPermissions(formId);
        if (definitionJson == null) {
            return Set.of();
        }
        Set<String> denied = new java.util.LinkedHashSet<>(fieldPermissionService.viewDeniedFields(loginUser,
                fieldPermissionService.parse(definitionJson)));
        // 子字段键展开：子字段权限键 = TABLE字段名.子字段名
        try {
            var perms = fieldPermissionService.parse(definitionJson);
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode fieldsArray = root.get("fields");
            if (fieldsArray != null && fieldsArray.isArray()) {
                for (JsonNode field : fieldsArray) {
                    if (!"TABLE".equals(field.path("type").asText())) continue;
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

    private String loadDefinitionJsonForPermissions(String formId) {
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormConfigEntity>()
                        .eq(FormConfigEntity::getFormId, formId)
                        .isNull(FormConfigEntity::getParentTable)
        );
        FormConfigEntity config = (configs != null && !configs.isEmpty()) ? configs.get(0) : null;
        return config != null ? config.getDefinition() : null;
    }

    /**
     * 加载 TABLE 字段的子字段元数据（字段名 → 类型）。
     */
    private Map<String, List<SubFieldMeta>> loadTableSubFields(String formId) {
        List<FormConfigEntity> configs = formConfigMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FormConfigEntity>()
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
                String type = fieldNode.has("type") ? fieldNode.get("type").asText() : "TEXT";
                if (!"TABLE".equals(type)) continue;

                String name = fieldNode.has("name") ? fieldNode.get("name").asText() : null;
                if (name == null) continue;

                JsonNode subFieldsNode = fieldNode.get("subFields");
                if (subFieldsNode == null || !subFieldsNode.isArray()) continue;

                List<SubFieldMeta> subFields = new ArrayList<>();
                for (JsonNode subNode : subFieldsNode) {
                    String subName = subNode.has("name") ? subNode.get("name").asText() : null;
                    if (subName == null) continue;
                    String subType = subNode.has("type") ? subNode.get("type").asText() : "TEXT";
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
    private List<String> buildSubTableProjection(List<SubFieldMeta> subFields) {
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
    private Map<String, String> parseSubTableMapping(String subTableMappingJson) {
        if (subTableMappingJson == null || subTableMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(subTableMappingJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse sub-table mapping JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== 内部类型 ====================

    /**
     * SQL 过滤子句：SQL 片段 + 参数值。
     */
    private record FilterClause(String sql, Object value) {}

    /**
     * 子表字段元数据。
     */
    private record SubFieldMeta(String name, FieldType type, String physicalCol) {}
}
