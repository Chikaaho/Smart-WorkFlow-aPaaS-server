package com.sw.ck.form.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.jdbc.JdbcVendorSupport;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.value.DynamicData;
import com.sw.ck.form.api.dto.FormDataFilter;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.api.vo.FormDefVO;
import com.sw.ck.form.dto.workspace.WorkspaceAggregateReq;
import com.sw.ck.form.dto.workspace.WorkspaceMetric;
import com.sw.ck.form.dto.workspace.WorkspaceRowsReq;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.dynamic.FormWorkspaceAggEnum;
import com.sw.ck.form.entity.FormStatusEnum;
import com.sw.ck.form.vo.FormDataRecordVO;
import com.sw.ck.form.vo.WorkspaceAggregateVO;
import com.sw.ck.form.vo.WorkspaceFieldVO;
import com.sw.ck.form.vo.WorkspaceFieldsVO;
import com.sw.ck.form.vo.WorkspaceGroupVO;
import com.sw.ck.form.vo.WorkspaceOptionVO;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 工作台数据组件（受控聚合 / 列表 / 字段目录 / 记录数）：沿用列表查询的可见性、
 * 租户、记录数据范围和字段查看权限；越权字段整请求拒绝，不静默剔除。
 * 所有列名经 {@link DynamicTableSql#requireColumn}，值全部参数绑定。
 */
@Service
@Log4j2
class FormDataWorkspaceQueryService {

    /** 可分组/可展示的字段类型（不含子表、富文本、附件等大对象或非标量列）。 */
    private static final Set<FieldType> WORKSPACE_SCALAR_TYPES = EnumSet.of(
            FieldType.TEXT, FieldType.NUMBER, FieldType.DATE, FieldType.TIME, FieldType.BOOL,
            FieldType.DICT, FieldType.REFERENCE, FieldType.USER, FieldType.DEPT, FieldType.FORMULA
    );

    /** 聚合指标允许的统计方式。 */
    private static final Set<String> WORKSPACE_AGGS = Set.of(FormWorkspaceAggEnum.COUNT.name(), FormWorkspaceAggEnum.SUM.name(), FormWorkspaceAggEnum.AVG.name(), FormWorkspaceAggEnum.MIN.name(), FormWorkspaceAggEnum.MAX.name());

    private static final int WORKSPACE_MAX_METRICS = 5;
    private static final int WORKSPACE_MAX_GROUPS = 100;
    private static final int WORKSPACE_MAX_COLUMNS = 8;
    private static final int WORKSPACE_MAX_PAGE_SIZE = 50;

    @Autowired
    private FormDefService formDefService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FormDataQuerySupport support;
    @Autowired
    private FormDataFilterClauseBuilder clauseBuilder;

    /** 应用范围访问闸门（可选注入；不注入时不受本闸门约束）。 */
    @Autowired(required = false)
    private FormAppAccessGuard appAccessGuard;

    @Autowired(required = false)
    private FormFieldEnrichmentService enrichment;

    /**
     * 工作台字段目录：仅返回当前账号可见、可用于标量展示/聚合的字段。
     * 不返回子表、富文本、附件等字段，避免配置界面扩大读取范围。
     */
    WorkspaceFieldsVO workspaceFields(String formKey) {
        WorkspaceFormContext ctx = prepareWorkspaceForm(formKey);
        WorkspaceFieldsVO result = new WorkspaceFieldsVO();
        result.setFormKey(ctx.formDef().getFormKey());
        result.setFormVersion(ctx.formDef().getFormVersion());
        List<WorkspaceFieldVO> fields = new ArrayList<>();
        try {
            String definitionJson = support.loadDefinitionJsonForPermissions(ctx.formDef().getId());
            if (definitionJson != null && !definitionJson.isBlank()) {
                JsonNode root = objectMapper.readTree(definitionJson);
                JsonNode array = root == null ? null : root.get("fields");
                if (array != null && array.isArray()) {
                    for (JsonNode field : array) {
                        String name = field.path("name").asText("");
                        if (name.isBlank()) continue;
                        FieldType type = ctx.fieldTypes().get(name);
                        if (type == null || !type.isEnabled() || !WORKSPACE_SCALAR_TYPES.contains(type)) {
                            continue;
                        }
                        if (ctx.viewDenied().contains(name)) continue;
                        WorkspaceFieldVO item = new WorkspaceFieldVO();
                        item.setName(name);
                        item.setLabel(field.path("label").asText(
                                ctx.fieldLabels().getOrDefault(name, name)));
                        item.setType(type.name());
                        List<WorkspaceOptionVO> options = parseWorkspaceOptions(field, type);
                        if (!options.isEmpty()) item.setOptions(options);
                        fields.add(item);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Workspace fields parse failed: formKey={}, {}", formKey, e.getMessage());
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单定义解析失败，请稍后重试");
        }
        result.setFields(fields);
        return result;
    }

    /**
     * 工作台聚合查询：分组字段 + 1..5 个指标。
     * <p>沿用列表查询的可见性、租户、记录数据范围和字段查看权限；越权字段整请求拒绝，
     * 不静默剔除。所有列名经 {@link DynamicTableSql#requireColumn}，值全部参数绑定。</p>
     */
    WorkspaceAggregateVO aggregateWorkspaceData(String formKey, WorkspaceAggregateReq req) {
        if (req == null) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN, "聚合查询缺少请求体");
        }
        WorkspaceFormContext ctx = prepareWorkspaceForm(formKey);
        String groupBy = blankToNull(req.getGroupBy());
        String groupBy2 = blankToNull(req.getGroupBy2());
        if (groupBy2 != null && groupBy == null) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN, "第二分组必须与第一分组同时配置");
        }
        if (groupBy != null && groupBy.equals(groupBy2)) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN, "两个分组字段不能相同");
        }
        String groupPhysical1 = groupBy == null ? null : requireWorkspaceScalar(ctx, groupBy);
        String groupPhysical2 = groupBy2 == null ? null : requireWorkspaceScalar(ctx, groupBy2);

        List<WorkspaceMetric> requested = req.getMetrics() == null
                ? List.of() : req.getMetrics();
        if (requested.isEmpty()) {
            WorkspaceMetric count = new WorkspaceMetric();
            count.setAgg("COUNT");
            requested = List.of(count);
        }
        if (requested.size() > WORKSPACE_MAX_METRICS) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                    "聚合指标最多 " + WORKSPACE_MAX_METRICS + " 个");
        }
        List<MetricSpec> metrics = new ArrayList<>();
        for (WorkspaceMetric metric : requested) {
            if (metric == null || blankToNull(metric.getAgg()) == null) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN, "聚合指标缺少统计方式");
            }
            String agg = metric.getAgg().trim().toUpperCase(Locale.ROOT);
            if (!WORKSPACE_AGGS.contains(agg)) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                        "不支持的统计方式: " + metric.getAgg());
            }
            String field = blankToNull(metric.getField());
            String physical = null;
            if (field != null) {
                FieldType type = ctx.fieldTypes().get(field);
                if (type == null || ctx.viewDenied().contains(field)) {
                    throw unknownWorkspaceField(ctx, field);
                }
                if (FormWorkspaceAggEnum.COUNT.name().equals(agg)) {
                    if (!type.isEnabled() || type == FieldType.TABLE || type == FieldType.LABEL) {
                        throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE,
                                "字段「" + ctx.fieldLabels().getOrDefault(field, field) + "」不支持计数");
                    }
                    physical = DynamicTableSql.requireColumn(field, type);
                } else {
                    if (type != FieldType.NUMBER && type != FieldType.FORMULA) {
                        throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE,
                                "字段「" + ctx.fieldLabels().getOrDefault(field, field)
                                        + "」不是数值字段，不能做 " + agg);
                    }
                    physical = DynamicTableSql.requireColumn(field, type);
                }
            } else if (!FormWorkspaceAggEnum.COUNT.name().equals(agg)) {
                throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                        agg + " 统计必须指定字段");
            }
            metrics.add(new MetricSpec(agg, physical));
        }

        List<Object> params = new ArrayList<>();
        StringBuilder where = workspaceWhere(ctx, params, req.getFilters());

        List<String> select = new ArrayList<>();
        List<String> groupColumns = new ArrayList<>();
        if (groupPhysical1 != null) {
            select.add(DynamicTableSql.quote(groupPhysical1) + " AS \"g0\"");
            groupColumns.add(DynamicTableSql.quote(groupPhysical1));
        }
        if (groupPhysical2 != null) {
            select.add(DynamicTableSql.quote(groupPhysical2) + " AS \"g1\"");
            groupColumns.add(DynamicTableSql.quote(groupPhysical2));
        }
        for (int i = 0; i < metrics.size(); i++) {
            MetricSpec metric = metrics.get(i);
            String expression = "COUNT(*)".equals(metric.expression()) ? metric.expression()
                    : metric.agg() + "(" + DynamicTableSql.quote(metric.physical()) + ")";
            select.add(expression + " AS \"m" + i + "\"");
        }

        int limit = req.getLimit() == null ? 20 : req.getLimit();
        limit = Math.max(1, Math.min(WORKSPACE_MAX_GROUPS, limit));

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", select))
                .append(" FROM ").append(DynamicTableSql.quote(ctx.tableName()))
                .append(" WHERE ").append(where);
        if (!groupColumns.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupColumns));
        }
        sql.append(" ORDER BY \"m0\" DESC");
        String aggregateSql = JdbcVendorSupport.applyLimit(jdbcTemplate, sql.toString(), limit);
        List<Object> dataParams = new ArrayList<>(params);

        List<Map<String, Object>> rows;
        try {
            rows = DynamicTableSql.query(jdbcTemplate, ctx.tableName(), aggregateSql, dataParams.toArray());
        } catch (Exception e) {
            log.error("Workspace aggregate failed: formKey={}, sql={}", formKey, sql, e);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "统计查询未能完成，请稍后重试");
        }

        List<WorkspaceGroupVO> groups = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                WorkspaceGroupVO item = new WorkspaceGroupVO();
                if (groupBy != null) item.setKey(DynamicData.of(normalizeWorkspaceValue(row.get("g0"))));
                if (groupBy2 != null) item.setKey2(DynamicData.of(normalizeWorkspaceValue(row.get("g1"))));
                List<DynamicData> values = new ArrayList<>();
                for (int i = 0; i < metrics.size(); i++) {
                    values.add(DynamicData.of(normalizeWorkspaceValue(row.get("m" + i))));
                }
                item.setValues(values);
                groups.add(item);
            }
        }

        Long totalRecords;
        try {
            String countSql = "SELECT COUNT(*) FROM " + DynamicTableSql.quote(ctx.tableName())
                    + " WHERE " + where;
            totalRecords = DynamicTableSql.queryForLong(jdbcTemplate, ctx.tableName(),
                    countSql, params.toArray());
        } catch (Exception e) {
            log.error("Workspace aggregate total failed: formKey={}", formKey, e);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "统计查询未能完成，请稍后重试");
        }

        WorkspaceAggregateVO result = new WorkspaceAggregateVO();
        result.setFormKey(ctx.formDef().getFormKey());
        result.setFormVersion(ctx.formDef().getFormVersion());
        result.setGroups(groups);
        result.setTotalRecords(totalRecords == null ? 0L : totalRecords);
        return result;
    }

    /**
     * 工作台数据列表查询：显式列 + 过滤 + 排序 + 有界分页。
     * <p>列、过滤、排序字段均须当前账号可见；记录范围与普通表单列表同口径强制。</p>
     */
    PageResult<FormDataRecordVO> queryWorkspaceRows(String formKey, WorkspaceRowsReq req) {
        if (req == null) {
            throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN, "列表查询缺少请求体");
        }
        WorkspaceFormContext ctx = prepareWorkspaceForm(formKey);

        List<String> columns = new ArrayList<>();
        if (req.getColumns() == null || req.getColumns().isEmpty()) {
            for (Map.Entry<String, FieldType> entry : ctx.fieldTypes().entrySet()) {
                if (WORKSPACE_SCALAR_TYPES.contains(entry.getValue())
                        && !ctx.viewDenied().contains(entry.getKey())) {
                    columns.add(entry.getKey());
                    if (columns.size() >= 4) break;
                }
            }
        } else {
            for (String raw : req.getColumns()) {
                String column = blankToNull(raw);
                if (column == null || "id".equals(column) || columns.contains(column)) continue;
                requireWorkspaceScalar(ctx, column);
                columns.add(column);
                if (columns.size() > WORKSPACE_MAX_COLUMNS) {
                    throw new BaseException(FormErrorCode.FIELD_TYPE_UNKNOWN,
                            "列表列最多 " + WORKSPACE_MAX_COLUMNS + " 个");
                }
            }
        }
        if (columns.isEmpty()) {
            throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE,
                    "该表单没有可用于工作台列表的字段");
        }

        List<Object> params = new ArrayList<>();
        StringBuilder where = workspaceWhere(ctx, params, req.getFilters());

        String sortField = blankToNull(req.getSortField());
        String sortPhysical;
        if (sortField == null || "create_time".equals(sortField)) {
            sortPhysical = "create_time";
        } else {
            sortPhysical = requireWorkspaceScalar(ctx, sortField);
        }
        String sortOrder = blankToNull(req.getSortOrder());
        sortOrder = "ASC".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";

        int page = Math.max(1, req.getPageNum() == null ? 1 : req.getPageNum());
        int size = req.getPageSize() == null ? 10 : req.getPageSize();
        size = Math.max(1, Math.min(WORKSPACE_MAX_PAGE_SIZE, size));
        int offset = (page - 1) * size;

        List<String> select = new ArrayList<>();
        select.add(DynamicTableSql.quote("id"));
        for (String column : columns) {
            FieldType type = ctx.fieldTypes().get(column);
            String physical = DynamicTableSql.requireColumn(column, type);
            select.add(DynamicTableSql.quote(physical) + " AS " + DynamicTableSql.quote(column));
        }

        String sql = JdbcVendorSupport.applyLimit(jdbcTemplate,
                "SELECT " + String.join(", ", select)
                        + " FROM " + DynamicTableSql.quote(ctx.tableName())
                        + " WHERE " + where
                        + " ORDER BY " + DynamicTableSql.quote(sortPhysical) + " " + sortOrder,
                size, offset);
        List<Object> dataParams = new ArrayList<>(params);

        List<Map<String, Object>> records;
        try {
            records = DynamicTableSql.query(jdbcTemplate, ctx.tableName(), sql, dataParams.toArray());
        } catch (Exception e) {
            log.error("Workspace rows failed: formKey={}, sql={}", formKey, sql, e);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "列表查询未能完成，请稍后重试");
        }

        Long total;
        try {
            String countSql = "SELECT COUNT(*) FROM " + DynamicTableSql.quote(ctx.tableName())
                    + " WHERE " + where;
            total = DynamicTableSql.queryForLong(jdbcTemplate, ctx.tableName(),
                    countSql, params.toArray());
        } catch (Exception e) {
            log.error("Workspace rows total failed: formKey={}", formKey, e);
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "列表查询未能完成，请稍后重试");
        }

        PageResult<FormDataRecordVO> result = new PageResult<>();
        result.setRecords(records == null ? List.of()
                : records.stream().map(FormDataRecordVO::new).toList());
        result.setTotal(total == null ? 0L : total);
        result.setPageNum(page);
        result.setPageSize(size);
        return result;
    }

    /**
     * 工作台表单选择器的可见记录数：与列表查询同一可见性、租户与数据范围口径的 COUNT(*)。
     *
     * <p>仅供 sources 汇总展示；单个表单计数失败不阻断整个候选列表，按 0 记录并留日志。</p>
     */
    long workspaceRecordCount(String formKey) {
        WorkspaceFormContext ctx = prepareWorkspaceForm(formKey);
        List<Object> params = new ArrayList<>();
        StringBuilder where = workspaceWhere(ctx, params, null);
        String countSql = "SELECT COUNT(*) FROM " + DynamicTableSql.quote(ctx.tableName())
                + " WHERE " + where;
        try {
            Long total = DynamicTableSql.queryForLong(jdbcTemplate, ctx.tableName(),
                    countSql, params.toArray());
            return total == null ? 0L : total;
        } catch (Exception e) {
            log.error("Workspace sources count failed: formKey={}", formKey, e);
            return 0L;
        }
    }

    /**
     * 工作台数据查询共用的表单准入：登录、可见、动作、应用范围、发布状态、物理表。
     */
    private WorkspaceFormContext prepareWorkspaceForm(String formKey) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        FormDefVO formDef = formDefService.getFormDefByKey(formKey);
        if (formDef == null || !formDefService.isCurrentUserVisible(formKey)) {
            throw new BaseException(FormErrorCode.QUERY_FORM_NOT_EXIST, "表单 '" + formKey + "' 不存在");
        }
        if (enrichment != null) {
            enrichment.assertCurrentUserCanPerformAction(formDef.getId(), "view");
        }
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
        support.validateTableName(tableName);
        Map<String, FieldType> fieldTypes = support.loadFieldTypeMap(formDef.getId());
        Set<String> viewDenied = support.viewDeniedFields(loginUser, formDef.getId());
        return new WorkspaceFormContext(loginUser, formDef, tableName, fieldTypes, viewDenied,
                support.fieldDisplayFor(formDef.getId()));
    }

    /** 工作台查询 WHERE：有效行 + 租户 + 记录数据范围 + 调用方过滤。 */
    private StringBuilder workspaceWhere(WorkspaceFormContext ctx, List<Object> params,
                                         List<FormDataFilter> filters) {
        StringBuilder where = new StringBuilder("\"deleted\" = 0 AND \"tenant_id\" = ?");
        params.add(ctx.loginUser().getTenantId());
        var scope = support.resolveDataScope(ctx.loginUser());
        FormDataScopeSupport.appendWhere(where, params, scope);
        for (FilterClause clause : clauseBuilder.validateAndBuildClauses(filters, ctx.fieldTypes(),
                ctx.viewDenied(), ctx.fieldLabels())) {
            where.append(" AND ").append(clause.sql());
            params.add(clause.value());
        }
        return where;
    }

    /** 校验可分组/可展示字段并返回物理列名；未知或无查看权统一不确认存在性。 */
    private String requireWorkspaceScalar(WorkspaceFormContext ctx, String field) {
        FieldType type = ctx.fieldTypes().get(field);
        if (type == null || ctx.viewDenied().contains(field)) {
            throw unknownWorkspaceField(ctx, field);
        }
        if (!type.isEnabled() || !WORKSPACE_SCALAR_TYPES.contains(type)) {
            throw new BaseException(FormErrorCode.QUERY_FILTER_FIELD_NOT_FILTERABLE,
                    "字段「" + ctx.fieldLabels().getOrDefault(field, field) + "」不支持工作台展示或分组");
        }
        return DynamicTableSql.requireColumn(field, type);
    }

    private BaseException unknownWorkspaceField(WorkspaceFormContext ctx, String field) {
        return new BaseException(FormErrorCode.QUERY_FILTER_FIELD_UNKNOWN,
                new Object[]{field},
                "字段 '" + ctx.fieldLabels().getOrDefault(field, field) + "' 不在当前可访问字段中");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 选项解析：兼容字符串与 {label,value}/{text,key} 两种定义形状。 */
    private List<WorkspaceOptionVO> parseWorkspaceOptions(JsonNode field, FieldType type) {
        List<WorkspaceOptionVO> options = new ArrayList<>();
        if (type == FieldType.BOOL) {
            options.add(option("是", "1"));
            options.add(option("否", "0"));
            return options;
        }
        JsonNode array = field.get("options");
        if (array == null || !array.isArray()) return options;
        for (JsonNode option : array) {
            if (option.isTextual()) {
                options.add(option(option.asText(), option.asText()));
                continue;
            }
            String value = option.path("value").asText(option.path("key").asText(""));
            String label = option.path("label").asText(option.path("text").asText(value));
            if (!value.isBlank()) {
                options.add(option(label, value));
            }
        }
        return options;
    }

    private WorkspaceOptionVO option(String label, String value) {
        WorkspaceOptionVO option = new WorkspaceOptionVO();
        option.setLabel(label);
        option.setValue(value);
        return option;
    }

    /** JDBC 标量收敛：时间转 ISO 文本，避免不同驱动序列化差异。 */
    private Object normalizeWorkspaceValue(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof Time time) return time.toLocalTime().toString();
        if (value instanceof LocalDateTime dateTime) return dateTime.toString();
        if (value instanceof LocalDate date) return date.toString();
        return value;
    }

    private record MetricSpec(String agg, String physical) {
        String expression() {
            return FormWorkspaceAggEnum.COUNT.name().equals(agg) && physical == null ? "COUNT(*)" : agg + "(" + physical + ")";
        }
    }

    private record WorkspaceFormContext(LoginUser loginUser, FormDefVO formDef, String tableName,
                                        Map<String, FieldType> fieldTypes, Set<String> viewDenied,
                                        Map<String, String> fieldLabels) {
    }
}
