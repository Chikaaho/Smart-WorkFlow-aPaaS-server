package com.sw.ck.form.dynamic;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库方言枚举。
 * <p>
 * 封装 H2 与 PostgreSQL 两套方言的列类型映射与标识符引用规则。
 * 用枚举策略模式代替散落的 if-else 字符串拼接，新增方言时只需新增枚举常量。
 * </p>
 *
 * <h3>列类型映射表</h3>
 * <pre>
 * FieldType   │ H2              │ PostgreSQL
 * ────────────┼─────────────────┼──────────────────
 * TEXT        │ VARCHAR(1000)   │ VARCHAR(1000)
 * RICH_TEXT   │ CLOB            │ TEXT
 * NUMBER      │ NUMERIC(20,6)   │ NUMERIC(20,6)
 * DATE        │ TIMESTAMP       │ TIMESTAMP
 * BOOL        │ SMALLINT        │ SMALLINT
 * DICT        │ VARCHAR(100)    │ VARCHAR(100)
 * REFERENCE   │ VARCHAR(36)     │ VARCHAR(36)
 * TABLE       │ (无列)          │ (无列)
 * </pre>
 */
public enum VendorDialect {

    H2 {
        @Override
        public String columnType(FieldType fieldType) {
            return switch (fieldType) {
                case TEXT -> "VARCHAR(1000)";
                case RICH_TEXT -> "CLOB";
                case NUMBER -> "NUMERIC(20,6)";
                case DATE -> "TIMESTAMP";
                case BOOL -> "SMALLINT";
                case DICT -> "VARCHAR(100)";
                case REFERENCE -> "VARCHAR(36)";
                case MULTISELECT -> "VARCHAR(1000)";
                case ATTACHMENT, IMAGE -> "CLOB";
                case TABLE, LABEL -> throw new IllegalArgumentException(
                        FieldType.class.getSimpleName() + " " + fieldType + " is not a column type");
                // disabled 占位成员 — 无列映射
                case EMAIL, PHONE, URL, RATE, SLIDER ->
                        throw new IllegalArgumentException(
                                "FieldType " + fieldType + " is not enabled (disabled placeholder)");
            };
        }

        @Override
        public String wrapIdentifier(String name) {
            // H2 在 PostgreSQL 模式下，引用标识符保留大小写；
            // 我们的表名列名均为小写，加双引号避免与关键字冲突
            return "\"" + name + "\"";
        }
    },

    POSTGRESQL {
        @Override
        public String columnType(FieldType fieldType) {
            return switch (fieldType) {
                case TEXT -> "VARCHAR(1000)";
                case RICH_TEXT -> "TEXT";
                case NUMBER -> "NUMERIC(20,6)";
                case DATE -> "TIMESTAMP";
                case BOOL -> "SMALLINT";
                case DICT -> "VARCHAR(100)";
                case REFERENCE -> "VARCHAR(36)";
                case MULTISELECT -> "VARCHAR(1000)";
                case ATTACHMENT, IMAGE -> "TEXT";
                case TABLE, LABEL -> throw new IllegalArgumentException(
                        FieldType.class.getSimpleName() + " " + fieldType + " is not a column type");
                // disabled 占位成员 — 无列映射
                case EMAIL, PHONE, URL, RATE, SLIDER ->
                        throw new IllegalArgumentException(
                                "FieldType " + fieldType + " is not enabled (disabled placeholder)");
            };
        }

        @Override
        public String wrapIdentifier(String name) {
            // 动态字段允许安全的 camelCase 名称。显式引用必须保留原始大小写，
            // 否则 CREATE TABLE 会生成 applicantnote，而提交/查询路径按逻辑字段
            // 引用 "applicantNote"，两条路径在 PostgreSQL 上会分裂为不同列。
            return "\"" + name + "\"";
        }
    };

    /**
     * @param fieldType 字段类型（不允许 {@link FieldType#TABLE}）
     * @return 当前方言下的 SQL 列类型定义
     */
    public abstract String columnType(FieldType fieldType);

    /**
     * 将标识符（表名/列名）包装为带引号的安全形式。
     */
    public abstract String wrapIdentifier(String name);

    /**
     * 根据 JDBC 连接元数据自动检测方言。
     *
     * @param dataSource 数据源
     * @return 匹配的方言枚举，未知数据库默认返回 {@link #H2}
     */
    public static VendorDialect detect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (productName.contains("postgresql") || productName.contains("postgres")) {
                return POSTGRESQL;
            }
            return H2;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to detect database vendor from DataSource", e);
        }
    }
}
