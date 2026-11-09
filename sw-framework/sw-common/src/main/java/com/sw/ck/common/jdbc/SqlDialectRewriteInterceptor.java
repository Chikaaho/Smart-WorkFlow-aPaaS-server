package com.sw.ck.common.jdbc;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.sql.Connection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时 SQL 方言重写：把 PostgreSQL/H2 写法统一改写到目标库可执行的形式。
 * <p>
 * 只处理两类确定性的尾部/关键字写法，避免解析整条 SQL：
 * <ul>
 *   <li>尾部 {@code LIMIT n [OFFSET m]}：Oracle 改 {@code FETCH FIRST}/{@code OFFSET ... FETCH}，
 *       SQL Server 改 {@code OFFSET ... FETCH}（无 ORDER BY 时补 {@code ORDER BY (SELECT NULL)}）；</li>
 *   <li>SQL Server 的 {@code FOR UPDATE [SKIP LOCKED]}：改成表提示
 *       {@code WITH (UPDLOCK, ROWLOCK[, READPAST])}。</li>
 * </ul>
 * 解析失败或遇到无法安全改写的组合（Oracle 的 {@code FOR UPDATE}+LIMIT、非顶层 LIMIT、
 * SQL Server 无 ORDER BY 且带 OFFSET 等）直接抛异常，不静默放过。MySQL/MariaDB/达梦/
 * Kingbase/openGauss 原生支持 LIMIT，不改写。
 * <p>
 * 该拦截器挂在 {@link StatementHandler#prepare(Connection, Integer)} 上，看到的是分页插件
 * 处理后的最终 SQL；pgvector 的 {@code LIMIT ?} 只在 PostgreSQL 执行，不改写。
 */
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class SqlDialectRewriteInterceptor implements Interceptor {

    private static final Pattern TRAILING_LIMIT = Pattern.compile(
            "(?is)^(.*?)\\blimit\\s+([0-9]+|\\?)(?:\\s+offset\\s+([0-9]+|\\?))?\\s*;?\\s*$");
    private static final Pattern FOR_UPDATE = Pattern.compile("(?is)\\bfor\\s+update(?:\\s+skip\\s+locked)?\\b");
    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?is)\\bfrom\\s+([a-zA-Z0-9_.\"\\[\\]`]+)");
    private static final Map<String, DatabaseVendor> VENDOR_CACHE = new ConcurrentHashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        Connection connection = (Connection) invocation.getArgs()[0];
        if (connection == null) {
            return invocation.proceed();
        }
        DatabaseVendor vendor = resolveVendor(connection);
        MetaObject meta = SystemMetaObject.forObject(statementHandler);
        BoundSql boundSql = (BoundSql) meta.getValue("delegate.boundSql");
        String sql = boundSql.getSql();
        if (sql == null || sql.isBlank()) {
            return invocation.proceed();
        }
        String rewritten = rewrite(sql, vendor);
        if (!rewritten.equals(sql)) {
            meta.setValue("delegate.boundSql.sql", rewritten);
        }
        return invocation.proceed();
    }

    DatabaseVendor resolveVendor(Connection connection) {
        try {
            String url = connection.getMetaData().getURL();
            if (url != null) {
                DatabaseVendor cached = VENDOR_CACHE.get(url);
                if (cached != null) {
                    return cached;
                }
                DatabaseVendor detected = DatabaseVendor.detect(connection);
                VENDOR_CACHE.put(url, detected);
                return detected;
            }
            return DatabaseVendor.detect(connection);
        } catch (Exception e) {
            throw new IllegalStateException("无法确定数据库方言，拒绝执行未改写 SQL", e);
        }
    }

    String rewrite(String sql, DatabaseVendor vendor) {
        String working = sql;
        String masked = maskLiteralsAndComments(working);
        if (vendor == DatabaseVendor.SQL_SERVER && FOR_UPDATE.matcher(masked).find()) {
            working = rewriteForUpdateToHint(working, masked);
            masked = maskLiteralsAndComments(working);
        }
        Matcher matcher = TRAILING_LIMIT.matcher(masked);
        if (!matcher.matches()) {
            if (vendor == DatabaseVendor.ORACLE || vendor == DatabaseVendor.SQL_SERVER) {
                if (containsLimitOutsideLiteral(masked)) {
                    throw new IllegalStateException("LIMIT 不在顶层末尾，无法安全改写到 "
                            + vendor.displayName() + ": " + oneLine(sql));
                }
            }
            return working;
        }
        if (vendor != DatabaseVendor.ORACLE && vendor != DatabaseVendor.SQL_SERVER) {
            return working;
        }
        String prefix = matcher.group(1).stripTrailing();
        String limit = matcher.group(2);
        String offset = matcher.group(3);
        if (vendor == DatabaseVendor.ORACLE) {
            if (FOR_UPDATE.matcher(masked).find()) {
                throw new IllegalStateException("Oracle 不允许 FOR UPDATE 与 FETCH FIRST 同用: "
                        + oneLine(sql));
            }
            String suffix = offset == null
                    ? " FETCH FIRST " + limit + " ROWS ONLY"
                    : " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            return prefix + suffix;
        }
        String orderBy = hasTopLevelOrderBy(masked) ? "" : " ORDER BY (SELECT NULL)";
        String suffix = offset == null
                ? " OFFSET 0 ROWS FETCH NEXT " + limit + " ROWS ONLY"
                : " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        return prefix + orderBy + suffix;
    }

    private static boolean containsLimitOutsideLiteral(String masked) {
        return Pattern.compile("(?is)\\blimit\\b").matcher(masked).find();
    }

    private static String rewriteForUpdateToHint(String sql, String masked) {
        Matcher matcher = FOR_UPDATE.matcher(masked);
        if (!matcher.find()) {
            return sql;
        }
        boolean skipLocked = masked.substring(matcher.start(), matcher.end())
                .toLowerCase(Locale.ROOT).contains("skip");
        Matcher fromMatcher = FROM_TABLE.matcher(masked);
        if (!fromMatcher.find()) {
            throw new IllegalStateException("SQL Server FOR UPDATE 改写失败：找不到 FROM 表名: " + oneLine(sql));
        }
        String table = fromMatcher.group(1);
        String hint = skipLocked
                ? " WITH (UPDLOCK, ROWLOCK, READPAST)"
                : " WITH (UPDLOCK, ROWLOCK)";
        String withoutLock = masked.substring(0, matcher.start()) + masked.substring(matcher.end());
        // 用掩码定位，再按同一位置改原文；FOR UPDATE 在原文中的长度与掩码一致。
        String originalWithoutLock = sql.substring(0, matcher.start()) + sql.substring(matcher.end());
        int tableEnd = fromMatcher.end(1);
        if (tableEnd > originalWithoutLock.length()) {
            throw new IllegalStateException("SQL Server FOR UPDATE 改写失败：表名位置越界: " + oneLine(sql));
        }
        return (originalWithoutLock.substring(0, tableEnd) + hint
                + originalWithoutLock.substring(tableEnd)).stripTrailing();
    }

    private static boolean hasTopLevelOrderBy(String masked) {
        int depth = 0;
        String lower = masked.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && lower.startsWith("order by", i)
                    && (i == 0 || !isWordChar(lower.charAt(i - 1)))
                    && (i + 8 >= lower.length() || !isWordChar(lower.charAt(i + 8)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * 把字符串字面量、注释、引用标识符替换为等长空格，保留关键词位置。
     */
    static String maskLiteralsAndComments(String sql) {
        char[] chars = sql.toCharArray();
        char[] out = sql.toCharArray();
        boolean single = false;
        boolean doubleQuote = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            char next = i + 1 < chars.length ? chars[i + 1] : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                } else {
                    out[i] = ' ';
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                    blockComment = false;
                } else {
                    out[i] = ' ';
                }
                continue;
            }
            if (single) {
                if (c == '\\' && i + 1 < chars.length) {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                } else if (c == '\'' && next == '\'') {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                } else if (c == '\'') {
                    out[i] = ' ';
                    single = false;
                } else {
                    out[i] = ' ';
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '"' && next == '"') {
                    out[i] = ' ';
                    out[i + 1] = ' ';
                    i++;
                } else if (c == '"') {
                    out[i] = ' ';
                    doubleQuote = false;
                } else {
                    out[i] = ' ';
                }
                continue;
            }
            if (backtick) {
                if (c == '`') {
                    out[i] = ' ';
                    backtick = false;
                } else {
                    out[i] = ' ';
                }
                continue;
            }
            if (c == '-' && next == '-') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i++;
                lineComment = true;
            } else if (c == '/' && next == '*') {
                out[i] = ' ';
                out[i + 1] = ' ';
                i++;
                blockComment = true;
            } else if (c == '\'') {
                out[i] = ' ';
                single = true;
            } else if (c == '"') {
                out[i] = ' ';
                doubleQuote = true;
            } else if (c == '`') {
                out[i] = ' ';
                backtick = true;
            }
        }
        return new String(out);
    }

    private static String oneLine(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
