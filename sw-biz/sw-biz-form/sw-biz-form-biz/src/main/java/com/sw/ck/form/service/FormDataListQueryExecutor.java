package com.sw.ck.form.service;

import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.jdbc.JdbcVendorSupport;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDataQueryRequest;
import com.sw.ck.form.api.vo.FormDefVO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 表单数据列表分页查询执行（对已发布表单的动态宽表）。
 * 全部 SQL 经 {@link DynamicTableSql} 受控入口构造与执行：
 * 表名/列名在构造边界校验，{@code "deleted" = 0 AND "tenant_id" = ?} 由入口强制，
 * 值一律 {@code ?} 绑定，缺失即拒绝执行。
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
@Log4j2
class FormDataListQueryExecutor {

    /** 分页硬上限 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 默认分页大小（对齐 PageParam 默认值） */
    private static final int DEFAULT_PAGE_SIZE = 10;

    @Autowired
    private FormDefService formDefService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FormDataQuerySupport support;
    @Autowired
    private FormDataFilterClauseBuilder clauseBuilder;

    // ==== I2 依赖（可选注入；缺省时按登录态降级解析） ====

    /** 应用范围访问闸门（可选注入；不注入时不受本闸门约束）。 */
    @Autowired(required = false)
    private FormAppAccessGuard appAccessGuard;

    @Autowired(required = false)
    private FormFieldEnrichmentService enrichment;

    /**
     * 查询表单数据（分页 + 过滤），并允许调用方指定更大的单页上限（导出等有界消费方）。
     * <p>maxPageSize 仍为硬上限：请求超过该值时被钳制到该值。</p>
     */
    PageResult<Map<String, Object>> query(String formKey, FormDataQueryRequest request,
                                          DataScopeFilter scopeFilter, int maxPageSize) {
        return doQueryFormData(formKey, request, scopeFilter, maxPageSize);
    }

    /** 查询表单数据（分页 + 过滤），附带数据范围过滤（导出等有界消费方使用）。 */
    PageResult<Map<String, Object>> query(String formKey, FormDataQueryRequest request,
                                          DataScopeFilter scopeFilter) {
        return doQueryFormData(formKey, request, scopeFilter, MAX_PAGE_SIZE);
    }

    /** 查询表单数据（分页 + 过滤），不做额外数据范围过滤（保持既有语义，租户边界恒生效）。 */
    PageResult<Map<String, Object>> query(String formKey, FormDataQueryRequest request) {
        return doQueryFormData(formKey, request, null, MAX_PAGE_SIZE);
    }

    private PageResult<Map<String, Object>> doQueryFormData(String formKey, FormDataQueryRequest request,
                                                            DataScopeFilter scopeFilter, int maxPageSize) {
        // —— Step 1: 获取当前用户 ——
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        Long tenantId = loginUser.getTenantId();

        // —— Step 2: 解析 formKey → FormDefVO（含 status + physicalTableName） ——
        FormDefVO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (!formDefService.isCurrentUserVisible(formKey)) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (enrichment != null) {
            enrichment.assertCurrentUserCanPerformAction(formDef.getId(), "view");
        }
        // 应用范围闸门：绑定应用的表单，应用不可见/停用时不允许列表访问（批量新访问，无记录级回退）。
        if (appAccessGuard != null) {
            appAccessGuard.requireNewBusiness(formKey, formDef.getAppId());
        }
        if (!FormStatusEnum.PUBLISHED.getCode().equals(formDef.getStatus()) && !FormStatusEnum.DISABLED.getCode().equals(formDef.getStatus())) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 未发布，不能查询");
        }
        String tableName = formDef.getPhysicalTableName();
        if (tableName == null || tableName.isBlank()) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST,
                    "该表单尚未完成数据表初始化，请联系管理员处理");
        }

        // —— Step 2.5: 表名防御性校验 ——
        support.validateTableName(tableName);

        // —— Step 3: 加载 definition JSON 并解析字段类型 ——
        Map<String, FieldType> fieldTypeMap = support.loadFieldTypeMap(formDef.getId());

        // —— I2: 当前身份无 view 权的字段（投影剔除 + 筛选拒绝，敏感值不可侧漏） ——
        Set<String> viewDenied = support.viewDeniedFields(loginUser, formDef.getId());

        // —— Step 4: 校验过滤条件 ——
        List<FilterClause> clauses = clauseBuilder.validateAndBuildClauses(request.getFilters(), fieldTypeMap,
                viewDenied, support.fieldDisplayFor(formDef.getId()));

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
                : support.resolveDataScope(loginUser);
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
        String dataSql = JdbcVendorSupport.applyLimit(jdbcTemplate,
                "SELECT " + columns + " FROM " + DynamicTableSql.quote(tableName) + " WHERE " + whereSql
                        + " ORDER BY \"create_time\" DESC", size, offset);

        List<Object> dataParams = new ArrayList<>(filterParams);

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
}
