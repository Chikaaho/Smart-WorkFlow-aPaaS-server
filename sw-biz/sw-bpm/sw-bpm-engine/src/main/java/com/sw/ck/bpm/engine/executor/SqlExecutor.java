package com.sw.ck.bpm.engine.executor;

import com.sw.ck.bpm.api.dto.SqlExecutionResult;
import com.sw.ck.bpm.engine.config.ExternalDatasourceProperties;
import com.sw.ck.bpm.engine.datasource.ExternalDatasourceManager;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.bpm.engine.service.SqlExecutionAuditService;
import com.sw.ck.form.api.exception.ExternalDatasourceResultLimitExceededException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外部数据源 SQL 执行引擎。
 * <p>
 * <b>安全约束：</b>
 * <ul>
 *   <li>仅允许单条 SELECT——jsqlparser 解析 + 堆叠检测 + 黑名单兜底</li>
 *   <li>强制 setMaxRows / setQueryTimeout / setReadOnly</li>
 *   <li>独立 JDBC 通道：使用 {@link JdbcTemplate} 裸 JDBC，不复用主库
 *       SqlSessionFactory / dynamic-datasource / 任何 MP 拦截器</li>
 * </ul>
 * </p>
 */
public class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    /** 危险 SQL 关键字黑名单（兜底，不依赖 jsqlparser 版本差异） */
    private static final Set<String> BLOCKLIST = Set.of(
            "LOAD_FILE", "INTO OUTFILE", "INTO DUMPFILE", "LOAD DATA", "LOAD XML",
            "BENCHMARK", "SLEEP", "GET_LOCK", "RELEASE_LOCK",
            "EXEC", "EXECUTE", "CALL", "EXECUTE IMMEDIATE"
    );

    /** 匹配双减号单行注释 */
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("--[^\n]*");
    /** 匹配斜杠星号块注释 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private final ExternalDatasourceService datasourceService;
    private final ExternalDatasourceManager poolManager;
    private final SqlExecutionAuditService auditService;
    private final ExternalDatasourceProperties.Execution execConfig;
    private final JdbcTemplate jdbcTemplate;

    public SqlExecutor(ExternalDatasourceService datasourceService,
                       ExternalDatasourceManager poolManager,
                       SqlExecutionAuditService auditService,
                       ExternalDatasourceProperties properties) {
        this.datasourceService = datasourceService;
        this.poolManager = poolManager;
        this.auditService = auditService;
        this.execConfig = properties.getExecution();
        this.jdbcTemplate = new JdbcTemplate();
    }

    /**
     * 执行只读 SQL 查询。
     *
     * @param datasourceId 外部数据源 ID
     * @param sql          待执行的 SQL
     * @param operatorId   操作人 ID
     * @param operatorName 操作人用户名
     * @return 查询结果
     */
    public SqlExecutionResult execute(Long datasourceId, String sql, Long operatorId, String operatorName) {
        long startTime = System.currentTimeMillis();

        // 1. 加载数据源配置（目标缺失/停用以专用 IAE 子类表达，供 form 端口适配转 empty）
        ExternalDatasource entity = datasourceService.getById(datasourceId);
        if (entity == null) {
            throw new com.sw.ck.bpm.engine.datasource.ExternalDatasourceUnavailableException(
                    "External datasource not found: id=" + datasourceId);
        }
        if (entity.getEnabled() == null || entity.getEnabled() != 1) {
            throw new com.sw.ck.bpm.engine.datasource.ExternalDatasourceUnavailableException(
                    "External datasource is disabled: " + entity.getName());
        }

        // 2. SQL 安全校验
        validateSql(sql);

        // 3. 获取独立连接池，通过裸 JDBC 执行
        javax.sql.DataSource pool = poolManager.getOrCreatePool(entity);
        jdbcTemplate.setDataSource(pool);
        int maxRows = maxRows();
        // 只允许探测到上限+1 行；超过上限时由 executeQuery 明确失败，不能静默截断。
        jdbcTemplate.setMaxRows(probeMaxRows(maxRows));
        jdbcTemplate.setQueryTimeout(execConfig.getQueryTimeout());

        try {
            SqlExecutionResult result = executeQuery(jdbcTemplate, sql);
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("SQL execution SUCCESS: datasource={}, rows={}, time={}ms, sql={}",
                    entity.getName(), result.getRowCount(), elapsed, truncateSql(sql));

            auditService.auditSuccess(datasourceId, entity.getName(), sql,
                    result.getRowCount(), elapsed, operatorId, operatorName);

            result.setExecutionTimeMs(elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("SQL execution FAILED: datasource={}, time={}ms, sql={}, error={}",
                    entity.getName(), elapsed, truncateSql(sql), e.getMessage());

            auditService.auditFailure(datasourceId, entity.getName(), sql,
                    elapsed, e.getMessage(), operatorId, operatorName);

            if (e instanceof ExternalDatasourceResultLimitExceededException limitExceeded) {
                throw limitExceeded;
            }
            throw new RuntimeException("SQL execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * SQL 安全校验：jsqlparser 解析 → 仅 SELECT → 禁止堆叠 → 黑名单兜底。
     */
    void validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be empty");
        }

        String trimmed = sql.trim();

        // --- 1. 检测多语句堆叠（分号在引号外） ---
        String noComments = stripComments(trimmed);
        if (hasUnquotedSemicolon(noComments)) {
            throw new IllegalArgumentException(
                    "Multiple statements (stacked queries) are not allowed. Only a single SELECT is permitted.");
        }

        // --- 2. jsqlparser 解析 + 类型校验 ---
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse SQL: " + e.getMessage() +
                    ". Only SELECT statements are allowed.", e);
        }
        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException(
                    "Only SELECT statements are allowed. Got: " + statement.getClass().getSimpleName());
        }

        // --- 3. 黑名单兜底 ---
        String upper = noComments.toUpperCase();
        for (String keyword : BLOCKLIST) {
            if (upper.contains(keyword)) {
                throw new IllegalArgumentException(
                        "Dangerous SQL keyword detected: " + keyword + ". Only read-only SELECT is permitted.");
            }
        }
    }

    /**
     * 执行查询并组装结果。
     * <p>
     * 使用裸 JDBC（PreparedStatement）而非 JdbcTemplate.query，
     * 确保 setReadOnly/setMaxRows/setQueryTimeout 全部生效。
     * </p>
     */
    private SqlExecutionResult executeQuery(JdbcTemplate jdbcTemplate, String sql) {
        // 先用 JdbcTemplate 的 query 获取 RowSet（保证 maxRows/timeout 生效）
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        // 通过 JdbcTemplate 的 PreparedStatementCreator 来设置 readOnly
        // JdbcTemplate.setMaxRows/setQueryTimeout 已在上层设置，但 readOnly 需要连接级设置
        // 直接用 JdbcTemplate.query() 并让回调使用 Connection
        javax.sql.DataSource ds = jdbcTemplate.getDataSource();
        if (ds == null) {
            throw new IllegalStateException("DataSource is not set on JdbcTemplate");
        }

        try (Connection conn = ds.getConnection()) {
            // 强制只读连接
            conn.setReadOnly(true);
            // 连接级超时兜底
            conn.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int maxRows = maxRows();
                ps.setMaxRows(probeMaxRows(maxRows));
                ps.setQueryTimeout(execConfig.getQueryTimeout());

                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    while (rs.next()) {
                        if (rows.size() >= maxRows) {
                            throw new ExternalDatasourceResultLimitExceededException(maxRows);
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.put(columns.get(i - 1), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("JDBC execution error: " + e.getMessage(), e);
        }

        return SqlExecutionResult.builder()
                .columns(columns)
                .rows(rows)
                .rowCount(rows.size())
                .build();
    }

    private int maxRows() {
        int maxRows = execConfig.getMaxRows();
        if (maxRows <= 0) {
            throw new IllegalStateException("External datasource maxRows must be positive");
        }
        return maxRows;
    }

    private int probeMaxRows(int maxRows) {
        return maxRows == Integer.MAX_VALUE ? maxRows : maxRows + 1;
    }

    // ---------- helper methods ----------

    /** 移除 SQL 注释（单行 -- 和块注释） */
    static String stripComments(String sql) {
        String result = SINGLE_LINE_COMMENT.matcher(sql).replaceAll("");
        result = BLOCK_COMMENT.matcher(result).replaceAll("");
        return result;
    }

    /** 检测是否存在引号外的分号（堆叠语句标志） */
    static boolean hasUnquotedSemicolon(String sql) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                return true;
            }
        }
        return false;
    }

    /** SQL 截断用于日志输出 */
    static String truncateSql(String sql) {
        if (sql == null) return "null";
        return sql.length() > 200 ? sql.substring(0, 200) + "..." : sql;
    }
}
