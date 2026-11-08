package com.sw.ck.common.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 主业务库的数据库厂商与方言能力。
 * <p>
 * 只承载跨模块共享的方言事实：标识符引用、LIMIT/取首行、行锁支持、字符串转换。
 * 表单动态宽表的字段类型映射仍由 {@code VendorDialect} 负责，二者通过本枚举共用同一套
 * 厂商识别，避免各模块各写一份 {@code DatabaseMetaData} 判断。
 * </p>
 *
 * <p>识别顺序：JDBC URL 前缀优先，其次 DatabaseMetaData 产品名；无法识别时 fail-fast，
 * 不使用 H2 兜底，避免在未知数据库上静默生成错误方言。</p>
 */
public enum DatabaseVendor {

    H2("H2"),
    POSTGRESQL("PostgreSQL"),
    MYSQL("MySQL"),
    MARIADB("MariaDB"),
    ORACLE("Oracle"),
    SQL_SERVER("SQL Server"),
    DM("达梦 DM"),
    KINGBASE("KingbaseES"),
    OPENGAUSS("openGauss");

    private final String displayName;

    DatabaseVendor(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** prod-update 目录名（与 Flyway/Spring 的 vendor 目录约定一致）。 */
    public String prodUpdateDir() {
        return switch (this) {
            case H2 -> "h2";
            case POSTGRESQL -> "postgresql";
            case MYSQL -> "mysql";
            case MARIADB -> "mariadb";
            case ORACLE -> "oracle";
            case SQL_SERVER -> "sqlserver";
            case DM -> "dm";
            case KINGBASE -> "kingbase";
            case OPENGAUSS -> "opengauss";
        };
    }

    /**
     * 从连接识别数据库厂商。
     *
     * @throws IllegalStateException 无法识别时
     */
    public static DatabaseVendor detect(Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            DatabaseVendor byUrl = fromJdbcUrl(metaData.getURL());
            if (byUrl != null) {
                return byUrl;
            }
            DatabaseVendor byProduct = fromProductName(metaData.getDatabaseProductName());
            if (byProduct != null) {
                return byProduct;
            }
            throw new IllegalStateException("不支持的数据库: product="
                    + metaData.getDatabaseProductName() + ", url=" + metaData.getURL());
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取数据库元数据，不能确定方言", e);
        }
    }

    static DatabaseVendor fromJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:h2:")) {
            return H2;
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL;
        }
        if (lower.startsWith("jdbc:mariadb:")) {
            return MARIADB;
        }
        if (lower.startsWith("jdbc:mysql:")) {
            return MYSQL;
        }
        if (lower.startsWith("jdbc:oracle:")) {
            return ORACLE;
        }
        if (lower.startsWith("jdbc:sqlserver:") || lower.startsWith("jdbc:microsoft:")) {
            return SQL_SERVER;
        }
        if (lower.startsWith("jdbc:dm:")) {
            return DM;
        }
        if (lower.startsWith("jdbc:kingbase")) {
            return KINGBASE;
        }
        if (lower.startsWith("jdbc:opengauss:") || lower.startsWith("jdbc:gaussdb:")) {
            return OPENGAUSS;
        }
        return null;
    }

    static DatabaseVendor fromProductName(String productName) {
        if (productName == null) {
            return null;
        }
        String p = productName.toLowerCase(Locale.ROOT);
        if (p.contains("h2")) {
            return H2;
        }
        if (p.contains("mariadb")) {
            return MARIADB;
        }
        if (p.contains("mysql")) {
            return MYSQL;
        }
        if (p.contains("postgres")) {
            return POSTGRESQL;
        }
        if (p.contains("kingbase")) {
            return KINGBASE;
        }
        if (p.contains("opengauss") || p.contains("gaussdb")) {
            return OPENGAUSS;
        }
        if (p.contains("oracle")) {
            return ORACLE;
        }
        if (p.contains("microsoft sql server") || p.contains("sql server")) {
            return SQL_SERVER;
        }
        if (p.contains("dameng") || p.equals("dm") || p.startsWith("dm ")) {
            return DM;
        }
        return null;
    }

    /** 标识符引用符：MySQL/MariaDB 反引号，SQL Server 方括号，其余双引号。 */
    public String wrapIdentifier(String name) {
        return switch (this) {
            case MYSQL, MARIADB -> "`" + name + "`";
            case SQL_SERVER -> "[" + name + "]";
            default -> "\"" + name + "\"";
        };
    }

    /** 追加在 SELECT 末尾的取前 N 行子句。 */
    public String limitClause(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit 不能为负数: " + limit);
        }
        return switch (this) {
            case ORACLE -> " FETCH FIRST " + limit + " ROWS ONLY";
            case SQL_SERVER -> " OFFSET 0 ROWS FETCH NEXT " + limit + " ROWS ONLY";
            default -> " LIMIT " + limit;
        };
    }

    /** 追加在 SELECT 末尾的 LIMIT/OFFSET 子句；SQL Server 调用方必须保证 SQL 已有 ORDER BY。 */
    public String limitOffsetClause(long limit, long offset) {
        if (limit < 0 || offset < 0) {
            throw new IllegalArgumentException("limit/offset 不能为负数: " + limit + "/" + offset);
        }
        return switch (this) {
            case ORACLE, SQL_SERVER -> " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            default -> " LIMIT " + limit + " OFFSET " + offset;
        };
    }

    /**
     * 在完整 SELECT 后追加取前 N 行；SQL Server 没有 ORDER BY 时补
     * {@code ORDER BY (SELECT NULL)}，满足 OFFSET/FETCH 语法要求。
     */
    public String applyLimit(String sql, long limit) {
        String base = sql.stripTrailing();
        if (this == SQL_SERVER && !hasOrderBy(base)) {
            base = base + " ORDER BY (SELECT NULL)";
        }
        return base + limitClause(limit);
    }

    /** 在完整 SELECT 后追加 LIMIT/OFFSET；SQL Server 无 ORDER BY 时同上。 */
    public String applyLimit(String sql, long limit, long offset) {
        String base = sql.stripTrailing();
        if (this == SQL_SERVER && !hasOrderBy(base)) {
            base = base + " ORDER BY (SELECT NULL)";
        }
        return base + limitOffsetClause(limit, offset);
    }

    private static boolean hasOrderBy(String sql) {
        return Pattern.compile("(?is)\\border\\s+by\\b").matcher(sql).find();
    }

    /** 是否支持 {@code FOR UPDATE SKIP LOCKED}。达梦待实测，先按不支持处理。 */
    public boolean supportsSkipLocked() {
        return switch (this) {
            case POSTGRESQL, MYSQL, MARIADB, ORACLE, KINGBASE, OPENGAUSS -> true;
            default -> false;
        };
    }

    /** 字符串化表达式（用于把 BIGINT 列与 VARCHAR 列比较，避免 CAST 类型名方言差异）。 */
    public String castToString(String expression) {
        return "CONCAT('', " + expression + ")";
    }

    /** 动态宽表 BOOL 列类型：与表单既有 0/1 语义一致。 */
    public String formBooleanType() {
        return switch (this) {
            case MYSQL, MARIADB -> "TINYINT(1)";
            case ORACLE -> "NUMBER(1)";
            case SQL_SERVER -> "BIT";
            case DM -> "BIT";
            case POSTGRESQL, KINGBASE, OPENGAUSS, H2 -> "SMALLINT";
        };
    }

    /** 动态宽表 TEXT 列类型。 */
    public String formTextType(int chars) {
        return switch (this) {
            case ORACLE, DM -> "VARCHAR2(" + chars + " CHAR)";
            case SQL_SERVER -> "NVARCHAR(" + chars + ")";
            default -> "VARCHAR(" + chars + ")";
        };
    }

    /** 动态宽表大文本列类型。 */
    public String formLongTextType() {
        return switch (this) {
            case MYSQL, MARIADB -> "LONGTEXT";
            case ORACLE, DM -> "CLOB";
            case SQL_SERVER -> "NVARCHAR(MAX)";
            default -> "TEXT";
        };
    }
}
