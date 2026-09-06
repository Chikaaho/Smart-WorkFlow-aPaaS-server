package com.sw.ck.form.dynamic;

import java.util.*;

/**
 * 列名校验规则单一常量源。
 * <p>
 * 本类汇聚所有列名/表名校验所需的常量与工具方法，供三关共用：
 * <ol>
 *   <li>草稿保存 — 发布时校验字段名</li>
 *   <li>发布 — DynamicTableManager 建表前校验</li>
 *   <li>提交 — FormSubmitService 字段名校验</li>
 * </ol>
 * 所有校验常量定义于此一处，禁止在其他类重复定义（move-not-copy）。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * ColumnValidation.validateColumnName("full_name");        // void or throw
 * ColumnValidation.validFieldTypes();                       // Set<FieldType> 合法类型
 * }</pre>
 *
 * @see FieldType
 */
public final class ColumnValidation {

    private ColumnValidation() {
        // utility class
    }

    // ==================== 列名规则 ====================

    /** 列名最大长度（PostgreSQL 标识符上限 63） */
    public static final int MAX_COLUMN_NAME_LENGTH = 63;

    /**
     * 列名白名单正则：小写字母/下划线开头，后续允许大小写字母、数字和下划线。
     * <p>
     * 表单逻辑字段保持前端常用的 camelCase（例如 {@code applicantNote}），
     * 但仍只允许标识符字符；DDL 使用未加引号的标识符，PostgreSQL/H2 会按各自
     * 标准折叠大小写，因此不会引入可执行 SQL 片段。
     * </p>
     */
    public static final String COLUMN_NAME_PATTERN = "^[a-z_][a-zA-Z0-9_]*$";

    // ==================== 系统固定列名 ====================

    /**
     * 系统固定列名集合（对齐 FormBaseEntity 语义）。
     * 用于校验用户列名是否与系统列冲突。
     */
    public static final Set<String> SYSTEM_COLUMN_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "id", "tenant_id", "deleted", "create_time", "create_by",
            "update_time", "update_by", "version"
    )));

    // ==================== 保留字黑名单 ====================

    /**
     * SQL 保留字黑名单（不区分大小写）。
     * 任何列名若与此集合中的关键字匹配，将被拒绝。
     */
    public static final Set<String> RESERVED_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CHECK", "CREATE",
            "DELETE", "DESC", "DISTINCT", "DROP", "EXISTS", "FOREIGN", "FROM", "GRANT",
            "GROUP", "HAVING", "IN", "INDEX", "INSERT", "INTO", "IS", "KEY", "LIKE",
            "LIMIT", "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "PRIMARY",
            "REFERENCES", "REVOKE", "SELECT", "SET", "TABLE", "UNIQUE", "UPDATE", "VALUES", "WHERE",
            // 额外的常见关键字
            "CASCADE", "COMMENT", "COMMIT", "CONSTRAINT", "CURRENT", "CURRENT_TIMESTAMP",
            "DEFAULT", "DESC", "ELSE", "END", "ESCAPE", "EXCEPT", "EXEC", "EXECUTE",
            "EXISTS", "EXPLAIN", "FETCH", "FULL", "FUNCTION", "GLOBAL", "IDENTITY",
            "IF", "IGNORE", "ILIKE", "INNER", "INTERSECT", "INTERVAL", "INTO", "JOIN",
            "LEADING", "LEFT", "LOCAL", "MATCH", "MERGE", "NATURAL", "NEXT", "NO",
            "NULLS", "OF", "ONLY", "OUTER", "OVER", "OVERLAPS", "PARTITION", "POSITION",
            "PRECISION", "RANGE", "RECURSIVE", "REPLACE", "RESTRICT", "RETURNING",
            "RIGHT", "ROLLBACK", "ROW", "ROWS", "SCHEMA", "SESSION", "SIMILAR",
            "SOME", "START", "SYMMETRIC", "SYSTEM", "TABLESPACE", "TEMP", "TEMPORARY",
            "THEN", "TRAILING", "TRANSACTION", "TRIGGER", "TRUNCATE", "UNION", "UNNEST",
            "USING", "VACUUM", "VARYING", "VIEW", "WHEN", "WHENEVER", "WINDOW", "WITH", "WITHIN"
    )));

    // ==================== 前缀黑名单 ====================

    /** 列名前缀黑名单 */
    public static final String[] PREFIX_BLACKLIST = {"sys_", "sw_", "act_"};

    // ==================== 校验方法 ====================

    /**
     * 校验物理列名是否合法。
     * <p>
     * 此为 SQL 注入红线：任何用户输入的字段名在进入 DDL 前必须通过此方法。
     * 本方法为三关共用入口，同一定义，同一行为。
     * </p>
     *
     * @param name 待校验的列名
     * @throws IllegalArgumentException 列名不符合规则时抛出
     */
    public static void validateColumnName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Column name must not be null or empty");
        }

        String trimmed = name.trim();

        // —— 长度 ——
        if (trimmed.length() > MAX_COLUMN_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Column name too long (max " + MAX_COLUMN_NAME_LENGTH + "): '"
                            + trimmed + "' (" + trimmed.length() + " chars)");
        }

        // —— 正则白名单 ——
        if (!trimmed.matches(COLUMN_NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "Invalid column name: '" + trimmed + "' — must match " + COLUMN_NAME_PATTERN);
        }

        // —— 系统列冲突 ——
        if (SYSTEM_COLUMN_NAMES.contains(trimmed)) {
            throw new IllegalArgumentException(
                    "Column name conflicts with system column: '" + trimmed + "'");
        }

        // —— 前缀黑名单 ——
        for (String prefix : PREFIX_BLACKLIST) {
            if (trimmed.startsWith(prefix)) {
                throw new IllegalArgumentException(
                        "Column name must not start with '" + prefix + "': '" + trimmed + "'");
            }
        }

        // —— SQL 保留字 ——
        if (RESERVED_WORDS.contains(trimmed.toUpperCase())) {
            throw new IllegalArgumentException(
                    "Column name is a reserved SQL keyword: '" + trimmed + "'");
        }
    }

    /**
     * 返回当前启用的合法字段类型集合（enabled=true 的 FieldType）。
     * 供发布/提交校验使用——只有此集合中的类型允许在 physical table 上建列或校验。
     */
    public static Set<FieldType> validFieldTypes() {
        Set<FieldType> valid = EnumSet.noneOf(FieldType.class);
        for (FieldType ft : FieldType.values()) {
            if (ft.isEnabled()) {
                valid.add(ft);
            }
        }
        return valid;
    }

    // ==================== 物理列名映射（唯一出口） ====================

    /**
     * 逻辑字段名 → 物理列名转换（唯一出口）。
     * <p>
     * 发布建表与提交存值必须共用此方法，保证同一逻辑名落同一物理列。
     * </p>
     * <ul>
     *   <li>REFERENCE → {@code ref_{logicalName}_id}</li>
     *   <li>TABLE → 抛异常（TABLE 不产生列）</li>
     *   <li>其余类型 → 原值（logicalName）</li>
     * </ul>
     *
     * @param logicalName 字段逻辑名
     * @param fieldType   字段类型
     * @return 物理列名
     * @throws IllegalArgumentException 若 fieldType 为 TABLE 或 disabled 占位成员
     */
    public static String physicalColumnName(String logicalName, FieldType fieldType) {
        if (!fieldType.isEnabled()) {
            throw new IllegalArgumentException(
                    "FieldType " + fieldType + " is not enabled, cannot map to physical column");
        }
        if (fieldType == FieldType.TABLE) {
            throw new IllegalArgumentException("TABLE type fields do not produce a column in the parent table");
        }
        if (fieldType == FieldType.LABEL) {
            throw new IllegalArgumentException("LABEL type fields are non-input display text and do not produce a column");
        }
        if (fieldType == FieldType.REFERENCE) {
            return "ref_" + logicalName + "_id";
        }
        return logicalName;
    }
}
