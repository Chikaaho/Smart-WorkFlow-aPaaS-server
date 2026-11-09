package com.sw.ck.form.dynamic;

import com.sw.ck.common.jdbc.DatabaseVendor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 表单动态宽表的数据库方言。
 * <p>
 * 列类型映射仍按表单 FieldType 表达；标识符引用统一委托
 * {@link DatabaseVendor}，避免与运行时 SQL 重写/启动检查各自维护一份厂商判断。
 * 新增方言时在 {@link DatabaseVendor} 与本枚举同步增加常量。
 * </p>
 *
 * <h3>列类型映射（FORMULA/NUMBER 等数值列统一 NUMERIC(20,6)/DECIMAL(20,6)）</h3>
 * <pre>
 * FieldType   │ H2/PG/Kingbase/openGauss │ MySQL/MariaDB │ Oracle/达梦 │ SQL Server
 * ────────────┼──────────────────────────┼───────────────┼─────────────┼────────────
 * TEXT        │ VARCHAR(1000)            │ VARCHAR(1000) │ VARCHAR2(1000 CHAR) │ NVARCHAR(1000)
 * RICH_TEXT   │ CLOB/TEXT                │ LONGTEXT      │ CLOB        │ NVARCHAR(MAX)
 * BOOL        │ SMALLINT                 │ TINYINT(1)    │ NUMBER(1)/BIT │ BIT
 * DATE/TIME   │ TIMESTAMP/TIME           │ DATETIME/TIME │ TIMESTAMP   │ DATETIME2/TIME
 * </pre>
 */
public enum VendorDialect {

    H2(DatabaseVendor.H2),
    POSTGRESQL(DatabaseVendor.POSTGRESQL),
    MYSQL(DatabaseVendor.MYSQL),
    MARIADB(DatabaseVendor.MARIADB),
    ORACLE(DatabaseVendor.ORACLE),
    SQL_SERVER(DatabaseVendor.SQL_SERVER),
    DM(DatabaseVendor.DM),
    KINGBASE(DatabaseVendor.KINGBASE),
    OPENGAUSS(DatabaseVendor.OPENGAUSS);

    private final DatabaseVendor vendor;

    VendorDialect(DatabaseVendor vendor) {
        this.vendor = vendor;
    }

    public DatabaseVendor vendor() {
        return vendor;
    }

    public String columnType(FieldType fieldType) {
        return switch (this) {
            case H2 -> h2ColumnType(fieldType);
            case POSTGRESQL, KINGBASE, OPENGAUSS -> postgresColumnType(fieldType);
            case MYSQL, MARIADB -> mysqlColumnType(fieldType);
            case ORACLE -> oracleColumnType(fieldType);
            case SQL_SERVER -> sqlServerColumnType(fieldType);
            case DM -> dmColumnType(fieldType);
        };
    }

    private static String h2ColumnType(FieldType fieldType) {
        return switch (fieldType) {
            case TEXT -> "VARCHAR(1000)";
            case RICH_TEXT -> "CLOB";
            case NUMBER, FORMULA -> "NUMERIC(20,6)";
            case DATE -> "TIMESTAMP";
            case BOOL -> "SMALLINT";
            case DICT -> "VARCHAR(100)";
            case REFERENCE -> "VARCHAR(36)";
            case MULTISELECT -> "VARCHAR(1000)";
            case ATTACHMENT, IMAGE -> "CLOB";
            case TIME -> "TIME";
            case USER, DEPT -> "VARCHAR(64)";
            case DATASOURCE -> "CLOB";
            case TABLE, LABEL -> throw notColumn(fieldType);
            case EMAIL, PHONE, URL, RATE, SLIDER -> throw disabled(fieldType);
        };
    }

    private static String postgresColumnType(FieldType fieldType) {
        return switch (fieldType) {
            case TEXT -> "VARCHAR(1000)";
            case RICH_TEXT -> "TEXT";
            case NUMBER, FORMULA -> "NUMERIC(20,6)";
            case DATE -> "TIMESTAMP";
            case BOOL -> "SMALLINT";
            case DICT -> "VARCHAR(100)";
            case REFERENCE -> "VARCHAR(36)";
            case MULTISELECT -> "VARCHAR(1000)";
            case ATTACHMENT, IMAGE, DATASOURCE -> "TEXT";
            case TIME -> "TIME";
            case USER, DEPT -> "VARCHAR(64)";
            case TABLE, LABEL -> throw notColumn(fieldType);
            case EMAIL, PHONE, URL, RATE, SLIDER -> throw disabled(fieldType);
        };
    }

    private static String mysqlColumnType(FieldType fieldType) {
        return switch (fieldType) {
            case TEXT -> "VARCHAR(1000)";
            case RICH_TEXT, DATASOURCE -> "LONGTEXT";
            case NUMBER, FORMULA -> "DECIMAL(20,6)";
            case DATE -> "DATETIME";
            case BOOL -> "TINYINT(1)";
            case DICT -> "VARCHAR(100)";
            case REFERENCE -> "VARCHAR(36)";
            case MULTISELECT -> "VARCHAR(1000)";
            case ATTACHMENT, IMAGE -> "LONGTEXT";
            case TIME -> "TIME";
            case USER, DEPT -> "VARCHAR(64)";
            case TABLE, LABEL -> throw notColumn(fieldType);
            case EMAIL, PHONE, URL, RATE, SLIDER -> throw disabled(fieldType);
        };
    }

    private static String oracleColumnType(FieldType fieldType) {
        return switch (fieldType) {
            case TEXT -> "VARCHAR2(1000 CHAR)";
            case RICH_TEXT, ATTACHMENT, IMAGE, DATASOURCE -> "CLOB";
            case NUMBER, FORMULA -> "NUMBER(20,6)";
            case DATE -> "TIMESTAMP";
            case BOOL -> "NUMBER(1)";
            case DICT -> "VARCHAR2(100)";
            case REFERENCE -> "VARCHAR2(36)";
            case MULTISELECT -> "VARCHAR2(1000 CHAR)";
            case TIME -> "VARCHAR2(20)";
            case USER, DEPT -> "VARCHAR2(64)";
            case TABLE, LABEL -> throw notColumn(fieldType);
            case EMAIL, PHONE, URL, RATE, SLIDER -> throw disabled(fieldType);
        };
    }

    private static String sqlServerColumnType(FieldType fieldType) {
        return switch (fieldType) {
            case TEXT -> "NVARCHAR(1000)";
            case RICH_TEXT, ATTACHMENT, IMAGE, DATASOURCE -> "NVARCHAR(MAX)";
            case NUMBER, FORMULA -> "DECIMAL(20,6)";
            case DATE -> "DATETIME2";
            case BOOL -> "BIT";
            case DICT -> "NVARCHAR(100)";
            case REFERENCE -> "NVARCHAR(36)";
            case MULTISELECT -> "NVARCHAR(1000)";
            case TIME -> "TIME";
            case USER, DEPT -> "NVARCHAR(64)";
            case TABLE, LABEL -> throw notColumn(fieldType);
            case EMAIL, PHONE, URL, RATE, SLIDER -> throw disabled(fieldType);
        };
    }

    private static String dmColumnType(FieldType fieldType) {
        return switch (fieldType) {
            case TEXT -> "VARCHAR2(1000 CHAR)";
            case RICH_TEXT, ATTACHMENT, IMAGE, DATASOURCE -> "CLOB";
            case NUMBER, FORMULA -> "NUMBER(20,6)";
            case DATE -> "TIMESTAMP";
            case BOOL -> "BIT";
            case DICT -> "VARCHAR2(100)";
            case REFERENCE -> "VARCHAR2(36)";
            case MULTISELECT -> "VARCHAR2(1000 CHAR)";
            case TIME -> "VARCHAR2(20)";
            case USER, DEPT -> "VARCHAR2(64)";
            case TABLE, LABEL -> throw notColumn(fieldType);
            case EMAIL, PHONE, URL, RATE, SLIDER -> throw disabled(fieldType);
        };
    }

    private static IllegalArgumentException notColumn(FieldType fieldType) {
        return new IllegalArgumentException(
                FieldType.class.getSimpleName() + " " + fieldType + " is not a column type");
    }

    private static IllegalArgumentException disabled(FieldType fieldType) {
        return new IllegalArgumentException(
                "FieldType " + fieldType + " is not enabled (disabled placeholder)");
    }

    /**
     * 多选感知的列类型（P63）：USER/DEPT 多选值=ID 列表 JSON 字符串，列宽与
     * MULTISELECT 对齐为 VARCHAR(1000)；BF20261013-009 起 DICT 下拉多选同口径；
     * 其余类型/单选与 {@link #columnType(FieldType)} 一致。
     */
    public String columnType(FieldType fieldType, boolean multiple) {
        if (multiple
                && (fieldType == FieldType.USER || fieldType == FieldType.DEPT
                        || fieldType == FieldType.DICT)) {
            return switch (this) {
                case ORACLE, DM -> "VARCHAR2(1000 CHAR)";
                case SQL_SERVER -> "NVARCHAR(1000)";
                default -> "VARCHAR(1000)";
            };
        }
        return columnType(fieldType);
    }

    /** 标识符引用：MySQL/MariaDB 反引号，SQL Server 方括号，其余双引号。 */
    public String wrapIdentifier(String name) {
        return vendor.wrapIdentifier(name);
    }

    /**
     * 根据 JDBC 连接元数据自动检测方言。
     *
     * @throws IllegalStateException 无法识别时
     */
    public static VendorDialect detect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            return from(DatabaseVendor.detect(conn));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to detect database vendor from DataSource", e);
        }
    }

    static VendorDialect from(DatabaseVendor vendor) {
        for (VendorDialect dialect : values()) {
            if (dialect.vendor == vendor) {
                return dialect;
            }
        }
        throw new IllegalStateException("Unsupported database vendor: " + vendor);
    }
}
