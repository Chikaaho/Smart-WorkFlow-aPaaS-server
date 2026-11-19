package com.sw.ck.form.service;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FilterOp;
import com.sw.ck.form.api.dto.FormDataFilter;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.dynamic.FieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * 表单数据查询的过滤条件校验与 SQL 子句构建。
 * <p>
 * 对每个 filter 执行：
 * <ol>
 *   <li>字段名是否在 definition 中</li>
 *   <li>op 是否为 IN（v1 不支持）</li>
 *   <li>字段类型是否可筛选</li>
 *   <li>op 是否在该类型的合法集合中</li>
 * </ol>
 * 全部通过后将逻辑字段名转为物理列名，生成 SQL 片段与参数值
 * （{@link FilterClause}）。值一律 PreparedStatement ? 参数化绑定。
 * </p>
 */
@Service
class FormDataFilterClauseBuilder {

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

    List<FilterClause> validateAndBuildClauses(List<FormDataFilter> filters,
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
            if (fieldType == FieldType.LABEL) {
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
}
