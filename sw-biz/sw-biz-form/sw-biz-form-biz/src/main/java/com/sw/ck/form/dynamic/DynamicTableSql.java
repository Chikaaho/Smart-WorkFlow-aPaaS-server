package com.sw.ck.form.dynamic;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 动态宽表受控 SQL 入口（Phase 3 §5.A：统一入口 + 机械可守门）。
 *
 * <p>本类是动态宽表 SQL 的<b>唯一构造与执行出口</b>。它把此前分散在业务层的人工约定
 * 收敛为基础设施强制契约：</p>
 * <ol>
 *   <li><b>标识符在构造边界统一校验</b>：表名过 {@link #TABLE_NAME_PATTERN}；列名先经
 *       {@link ColumnValidation#physicalColumnName} 映射、再过 {@link ColumnValidation#validateColumnName}
 *       校验——名称映射不替代合法性校验，历史/越权元数据同样不能绕过。</li>
 *   <li><b>租户与逻辑删除由基础设施强制表达</b>：动态宽表的每条语句必须同时带
 *       {@code "deleted" = 0} 与 {@code "tenant_id" = ?}；缺失即在执行前拒绝
 *       （{@link FormErrorCode#DYNAMIC_SQL_CONTRACT_VIOLATION}），调用者无法通过遗漏条件读到
 *       跨租户或已删除数据。</li>
 *   <li><b>参数一律绑定</b>：占位符 {@code ?} 数量必须与参数个数一致，任何值拼接都会在执行前暴露。</li>
 *   <li><b>REFERENCE 串行化</b>：{@link #tryLockLiveRow} 对“被引用父记录行”加锁，
 *       覆盖“当前无引用行”场景，与删除路径共用同一锁身份（租户 + 表 + 记录）。</li>
 * </ol>
 *
 * <h3>允许的表</h3>
 * <ul>
 *   <li>动态宽表：{@code sw_form_{nanoId}} / {@code sw_form_table_{nanoId}}（校验正则）；</li>
 *   <li>固定元数据表：{@link #ALLOWED_METADATA_TABLES}（由 Flyway 建，非动态宽表；
 *       目前仅 {@code sw_form_config}，其跨租户扫描是 REFERENCE 反查的有意设计，
 *       因此该表只强制 {@code deleted} 条件）。</li>
 * </ul>
 *
 * <h3>行锁行为</h3>
 * <ul>
 *   <li>锁身份：{@code tenantId + 物理表名 + 记录 id}；</li>
 *   <li>锁序：<b>批量加锁按 (表名, 记录 id) 升序</b>，避免多引用场景的交叉死锁；</li>
 *   <li>等待上限：JVM 锁 {@value #LOCK_WAIT_MILLIS} ms + 数据库语句超时
 *       {@value #LOCK_QUERY_TIMEOUT_SECONDS} s，超时/死锁统一映射为
 *       {@link FormErrorCode#DYNAMIC_ROW_LOCK_TIMEOUT}（可重试），由调用方事务回滚；</li>
 *   <li>持有期：行锁与 JVM 锁均持有到事务结束（{@code afterCompletion} 释放）；无事务上下文
 *       （只读校验路径）退化为一次存在性检查，不持有锁。</li>
 * </ul>
 */
public final class DynamicTableSql {

    private static final Logger log = LoggerFactory.getLogger(DynamicTableSql.class);

    // ==================== 标识符规则（唯一来源） ====================

    /** 动态宽表物理表名正则（建表、查询、更新、删除、导入导出共用同一常量）。 */
    public static final String TABLE_NAME_PATTERN = "^sw_form(_table)?_[a-z][a-z0-9]{9}$";

    private static final Pattern TABLE_NAME = Pattern.compile(TABLE_NAME_PATTERN);

    /** 标识符字符集（含 camelCase 用户列）；除此之外一律拒绝。 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * 允许在受控入口内出现的固定元数据表（非动态宽表，不建立租户列强制）。
     * <p>新增成员必须同时说明其租户语义，否则视为未分类旁路。</p>
     */
    public static final Set<String> ALLOWED_METADATA_TABLES = Set.of("sw_form_config");

    /** 元数据表“已删除”谓词存在性判据（该表由 Flyway 建，跨库大小写折叠，故不强制引号）。 */
    private static final Pattern DELETED_WORD = Pattern.compile("(?i)(?<![A-Za-z0-9_])deleted(?![A-Za-z0-9_])");

    /** 语句中出现的动态宽表标识（用于单表约束）。 */
    private static final Pattern DYNAMIC_TABLE_TOKEN = Pattern.compile("sw_form(?:_table)?_[a-z][a-z0-9]{9}");

    /** 动态宽表系统列（与 {@link DynamicTableManager#SYSTEM_COLUMNS} 语义一致）。 */
    public static final List<String> SYSTEM_COLUMNS = List.of(
            "id", "tenant_id", "deleted", "create_time", "create_by",
            "update_time", "update_by", "version"
    );

    /** 子表专用列名。 */
    public static final String PARENT_RECORD_COLUMN = "parent_record_id";

    // ==================== 行锁参数 ====================

    /** JVM 侧锁等待上限（毫秒）。 */
    public static final long LOCK_WAIT_MILLIS = 10_000L;

    /** 数据库侧语句超时（秒）；锁被占用超过该时长即中止并回滚。 */
    public static final int LOCK_QUERY_TIMEOUT_SECONDS = 10;

    /** 同一 (租户, 表, 记录) 的 JVM 锁登记表；事务结束后清理。 */
    private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private DynamicTableSql() {
    }

    // ==================== 标识符校验 ====================

    /** 是否为合法动态宽表物理表名。 */
    public static boolean isValidTableName(String tableName) {
        return tableName != null && TABLE_NAME.matcher(tableName).matches();
    }

    /**
     * 校验动态宽表物理表名；非法即拒绝执行。
     *
     * @param tableName 物理表名（来自注册表 / definition，非用户直接输入）
     * @return 原表名（便于链式使用）
     */
    public static String requireTableName(String tableName) {
        if (!isValidTableName(tableName)) {
            throw contractViolation("非法的动态宽表物理表名: " + tableName);
        }
        return tableName;
    }

    /**
     * 校验固定元数据表名是否在登记清单内。
     */
    public static String requireMetadataTable(String tableName) {
        if (tableName == null || !ALLOWED_METADATA_TABLES.contains(tableName)) {
            throw contractViolation("未登记的元数据表，禁止通过受控入口访问: " + tableName);
        }
        return tableName;
    }

    /**
     * 逻辑字段名 → 物理列名（映射 + 校验，单一出口）。
     * <p>映射（REFERENCE → {@code ref_{name}_id}）不构成合法性证明，映射结果必须再经列名白名单校验。</p>
     */
    public static String requireColumn(String logicalName, FieldType fieldType) {
        String physical = ColumnValidation.physicalColumnName(logicalName, fieldType);
        validatePhysicalColumn(physical);
        return physical;
    }

    /** 校验物理列名（白名单/系统列冲突/前缀黑名单/保留字）。 */
    public static void validatePhysicalColumn(String physicalColumnName) {
        try {
            ColumnValidation.validateColumnName(physicalColumnName);
        } catch (IllegalArgumentException e) {
            throw contractViolation("非法的动态宽表列名: " + physicalColumnName, e);
        }
    }

    /** 校验系统列/子表列（仅供基础设施自身的谓词与投影使用）。 */
    public static String requireSystemColumn(String columnName) {
        if (!SYSTEM_COLUMNS.contains(columnName) && !PARENT_RECORD_COLUMN.equals(columnName)) {
            throw contractViolation("非系统列不得按系统列引用: " + columnName);
        }
        return columnName;
    }

    /** 安全引用标识符（字符集白名单 + 双引号包裹）。 */
    public static String quote(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw contractViolation("非法标识符，拒绝拼接: " + identifier);
        }
        return "\"" + identifier + "\"";
    }

    // ==================== 强制谓词 ====================

    /** 动态宽表“有效行”谓词（逻辑删除 + 租户隔离）。 */
    public static String livePredicate() {
        return "\"deleted\" = 0 AND \"tenant_id\" = ?";
    }

    /**
     * 追加“有效行”谓词（含参数绑定）。
     *
     * @param where    目标 WHERE 片段
     * @param params   参数列表（追加租户值）
     * @param tenantId 当前租户（必须非空，缺失即拒绝）
     */
    public static void appendLivePredicate(StringBuilder where, List<Object> params, Long tenantId) {
        if (tenantId == null) {
            throw contractViolation("租户上下文缺失：动态宽表访问必须携带 tenant_id");
        }
        where.append(livePredicate());
        params.add(tenantId);
    }

    // ==================== 受控执行 ====================

    /**
     * 受控查询（返回行列表）。
     */
    public static List<Map<String, Object>> query(JdbcTemplate jdbcTemplate, String table,
                                                  String sql, Object... params) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Object[] bound = normalize(params);
        assertContract(table, sql, bound);
        return jdbcTemplate.queryForList(sql, bound);
    }

    /**
     * 受控查询（单值 Long）。
     */
    public static Long queryForLong(JdbcTemplate jdbcTemplate, String table, String sql, Object... params) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Object[] bound = normalize(params);
        assertContract(table, sql, bound);
        return jdbcTemplate.queryForObject(sql, Long.class, bound);
    }

    /**
     * 受控查询（单值 Integer）。
     */
    public static Integer queryForInt(JdbcTemplate jdbcTemplate, String table, String sql, Object... params) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Object[] bound = normalize(params);
        assertContract(table, sql, bound);
        return jdbcTemplate.queryForObject(sql, Integer.class, bound);
    }

    /**
     * 受控更新/插入/软删。
     */
    public static int update(JdbcTemplate jdbcTemplate, String table, String sql, Object... params) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Object[] bound = normalize(params);
        assertContract(table, sql, bound);
        return jdbcTemplate.update(sql, bound);
    }

    /**
     * 受控 DDL（建表/加列）。DDL 不承载租户与删除谓词，但仍须通过表名与语句形状校验。
     */
    public static void ddl(JdbcTemplate jdbcTemplate, String table, String sql) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        requireTableName(table);
        if (sql == null || sql.isBlank()) {
            throw contractViolation("DDL 语句为空");
        }
        String normalized = sql.toUpperCase(java.util.Locale.ROOT);
        if (!normalized.startsWith("CREATE TABLE ") && !normalized.startsWith("ALTER TABLE ")) {
            throw contractViolation("受控 DDL 仅允许 CREATE TABLE / ALTER TABLE: " + firstLine(sql));
        }
        if (sql.indexOf(';') >= 0 || sql.contains("--") || sql.contains("/*")) {
            throw contractViolation("DDL 禁止多语句或注释: " + firstLine(sql));
        }
        if (!sql.contains(quote(table))) {
            throw contractViolation("DDL 未引用声明表 " + table);
        }
        jdbcTemplate.execute(sql);
    }

    // ==================== REFERENCE 串行化 ====================

    /** 锁对象身份：物理表 + 记录 id（租户在调用侧统一给出）。 */
    public record LockTarget(String table, String recordId) implements Comparable<LockTarget> {
        public LockTarget {
            requireTableName(table);
            if (recordId == null || recordId.isBlank()) {
                throw contractViolation("锁对象记录 id 不能为空");
            }
        }

        @Override
        public int compareTo(LockTarget other) {
            int byTable = this.table.compareTo(other.table);
            return byTable != 0 ? byTable : this.recordId.compareTo(other.recordId);
        }
    }

    /**
     * 对“被引用父记录行”加锁并判定其是否存活（未删除且属于当前租户）。
     *
     * <p>删除路径与引用写入路径共用同一锁身份，因此：</p>
     * <ul>
     *   <li>引用先到：写侧持锁提交后，删除侧的 RESTRICT 反查一定能看到该引用；</li>
     *   <li>删除先到：删除侧持锁提交后，写侧的加锁查询已看不到存活父行，引用写入被拒绝；</li>
     *   <li>并发：两侧在同一父行上串行化，不存在“检查后、删除前”窗口。</li>
     * </ul>
     *
     * @return true = 父记录存活并已加锁；false = 父记录不存在/已删除（无锁可持）
     */
    public static boolean tryLockLiveRow(JdbcTemplate jdbcTemplate, String table,
                                         String recordId, Long tenantId) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        requireTableName(table);
        if (recordId == null || recordId.isBlank()) {
            throw contractViolation("记录 id 不能为空");
        }
        if (tenantId == null) {
            throw contractViolation("租户上下文缺失：REFERENCE 完整性校验必须携带 tenant_id");
        }

        String sql = "SELECT \"id\" FROM " + quote(table) + " WHERE \"id\" = ? AND "
                + livePredicate() + " FOR UPDATE";

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务上下文（只读校验路径）：FOR UPDATE 无持有期，退化为存在性检查并明确记录
            log.debug("REFERENCE 校验无事务上下文，按存在性检查执行（不持锁）: table={}, recordId={}",
                    table, recordId);
            return !query(jdbcTemplate, table, sql.replace(" FOR UPDATE", ""), recordId, tenantId).isEmpty();
        }

        String key = tenantId + "|" + table + "|" + recordId;
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = jvmLock.tryLock(LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw lockTimeout(key, e);
        }
        if (!acquired) {
            throw lockTimeout(key, null);
        }

        boolean releaseNow = false;
        try {
            List<Map<String, Object>> rows = queryForLock(jdbcTemplate, table, sql, recordId, tenantId);
            if (rows.isEmpty()) {
                releaseNow = true;
                return false;
            }
            registerRelease(key, jvmLock);
            log.debug("REFERENCE 父行已加锁: lockKey={}", key);
            return true;
        } catch (BaseException e) {
            releaseNow = true;
            throw e;
        } catch (RuntimeException e) {
            releaseNow = true;
            if (isLockConflict(e)) {
                throw lockTimeout(key, e);
            }
            throw e;
        } finally {
            if (releaseNow) {
                releaseJvmLock(key, jvmLock);
            }
        }
    }

    /**
     * 批量加锁：按 {@link LockTarget} 升序（表名 → 记录 id）逐个加锁，保证确定锁序。
     *
     * @return 每个目标是否存活（保持与入参相同的键集合语义；顺序无关）
     */
    public static Map<LockTarget, Boolean> tryLockLiveRows(JdbcTemplate jdbcTemplate,
                                                           Collection<LockTarget> targets,
                                                           Long tenantId) {
        Map<LockTarget, Boolean> live = new LinkedHashMap<>();
        if (targets == null || targets.isEmpty()) {
            return live;
        }
        List<LockTarget> ordered = new ArrayList<>(targets);
        java.util.Collections.sort(ordered);
        for (LockTarget target : ordered) {
            if (live.containsKey(target)) {
                continue;
            }
            live.put(target, tryLockLiveRow(jdbcTemplate, target.table(), target.recordId(), tenantId));
        }
        return live;
    }

    /** 当前进程内已登记的 JVM 锁数量（诊断用）。 */
    public static int registeredLockCount() {
        return JVM_LOCKS.size();
    }

    // ==================== 内部：契约断言 ====================

    private static void assertContract(String table, String sql, Object[] params) {
        if (sql == null || sql.isBlank()) {
            throw contractViolation("SQL 语句为空");
        }
        if (sql.indexOf(';') >= 0) {
            throw contractViolation("受控入口禁止多语句: " + firstLine(sql));
        }
        if (sql.contains("--") || sql.contains("/*")) {
            throw contractViolation("受控入口禁止 SQL 注释: " + firstLine(sql));
        }

        boolean dynamic = isValidTableName(table);
        if (dynamic) {
            if (!sql.contains(quote(table))) {
                throw contractViolation("SQL 未引用声明表 " + table + ": " + firstLine(sql));
            }
            // 单表约束：语句中出现的每个动态宽表都必须等于声明表，禁止借其它动态表旁路谓词校验
            java.util.regex.Matcher referenced = DYNAMIC_TABLE_TOKEN.matcher(sql);
            while (referenced.find()) {
                if (!table.equals(referenced.group())) {
                    throw contractViolation("受控入口禁止跨动态宽表访问（声明 " + table
                            + "，实际引用 " + referenced.group() + "）: " + firstLine(sql));
                }
            }
        } else {
            requireMetadataTable(table);
            if (!Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(table) + "(?![A-Za-z0-9_])")
                    .matcher(sql).find()) {
                throw contractViolation("SQL 未引用声明表 " + table + ": " + firstLine(sql));
            }
        }

        if (dynamic) {
            if (!sql.contains("\"deleted\"")) {
                throw contractViolation("动态宽表语句缺少 \"deleted\" 条件: " + firstLine(sql));
            }
            if (!sql.contains("\"tenant_id\"")) {
                throw contractViolation("动态宽表语句缺少 \"tenant_id\" 条件: " + firstLine(sql));
            }
        } else if (!DELETED_WORD.matcher(sql).find()) {
            throw contractViolation("元数据表语句缺少 deleted 条件: " + firstLine(sql));
        }

        int placeholders = countPlaceholders(sql);
        if (placeholders != params.length) {
            throw contractViolation("占位符与绑定参数数量不一致（" + placeholders + " vs " + params.length
                    + "），值必须全部通过 ? 绑定: " + firstLine(sql));
        }
    }

    /** 统计字符串外的 {@code ?} 占位符数量。 */
    static int countPlaceholders(String sql) {
        int count = 0;
        boolean inLiteral = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (c == '?' && !inLiteral) {
                count++;
            }
        }
        return count;
    }

    // ==================== 内部：锁 ====================

    private static List<Map<String, Object>> queryForLock(JdbcTemplate jdbcTemplate, String table,
                                                          String sql, Object... params) {
        Object[] bound = normalize(params);
        assertContract(table, sql, bound);
        return jdbcTemplate.query(sql,
                ps -> {
                    ps.setQueryTimeout(LOCK_QUERY_TIMEOUT_SECONDS);
                    for (int i = 0; i < bound.length; i++) {
                        StatementCreatorUtils.setParameterValue(ps, i + 1, SqlTypeValue.TYPE_UNKNOWN, bound[i]);
                    }
                },
                rs -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString(1));
                        rows.add(row);
                    }
                    return rows;
                });
    }

    private static void registerRelease(String lockKey, ReentrantLock jvmLock) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                releaseJvmLock(lockKey, jvmLock);
            }
        });
    }

    private static void releaseJvmLock(String lockKey, ReentrantLock jvmLock) {
        if (jvmLock.isHeldByCurrentThread()) {
            jvmLock.unlock();
        }
        if (!jvmLock.isLocked() && !jvmLock.hasQueuedThreads()) {
            JVM_LOCKS.remove(lockKey, jvmLock);
        }
    }

    private static boolean isLockConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && (state.equals("40P01") || state.equals("40001")
                        || state.equals("55P03") || state.equals("57014") || state.startsWith("HYT"))) {
                    return true;
                }
                String message = sqlException.getMessage();
                if (message != null && (message.contains("Timeout trying to lock")
                        || message.contains("lock timeout")
                        || message.contains("deadlock detected")
                        || message.contains("Lock wait timeout"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    // ==================== 内部：工具 ====================

    private static Object[] normalize(Object[] params) {
        return params == null ? new Object[0] : params;
    }

    private static String firstLine(String sql) {
        int index = sql.indexOf('\n');
        return index < 0 ? sql : sql.substring(0, index) + " …";
    }

    private static BaseException contractViolation(String message) {
        return contractViolation(message, null);
    }

    private static BaseException contractViolation(String message, Throwable cause) {
        log.error("[动态宽表受控入口] 契约违规: {}", message, cause);
        return new BaseException(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION, message);
    }

    private static BaseException lockTimeout(String lockKey, Throwable cause) {
        log.warn("[动态宽表受控入口] 行锁等待超时或死锁: lockKey={}", lockKey, cause);
        return new BaseException(FormErrorCode.DYNAMIC_ROW_LOCK_TIMEOUT,
                "该记录正在被其他操作占用（锁身份 " + lockKey + "），请稍后重试");
    }
}
