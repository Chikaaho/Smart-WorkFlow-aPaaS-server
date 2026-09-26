package com.sw.ck.form.dynamic;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控动态宽表入口的契约测试（真实 H2，非 mock）。
 *
 * <p>钉死四件事：标识符校验、租户/逻辑删除谓词强制、参数绑定数量一致性、
 * 以及 REFERENCE 父行锁的存活判定与事务结束释放。</p>
 */
@DisplayName("DynamicTableSql 受控入口契约")
class DynamicTableSqlContractTest {

    private static final String TABLE = "sw_form_abcdefghij";
    private static final String SUB_TABLE = "sw_form_table_klmnopqrst";

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:dynsql-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(ds);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + TABLE + "\"");
        jdbcTemplate.execute("CREATE TABLE \"" + TABLE + "\" ("
                + "\"id\" VARCHAR(36) PRIMARY KEY, \"tenant_id\" BIGINT, \"deleted\" SMALLINT DEFAULT 0, "
                + "\"name\" VARCHAR(100), \"ref_target_id\" VARCHAR(36))");
        jdbcTemplate.update("INSERT INTO \"" + TABLE + "\" (\"id\",\"tenant_id\",\"deleted\",\"name\") VALUES (?,?,0,?)",
                "r-t1", 1L, "租户1记录");
        jdbcTemplate.update("INSERT INTO \"" + TABLE + "\" (\"id\",\"tenant_id\",\"deleted\",\"name\") VALUES (?,?,0,?)",
                "r-t2", 2L, "租户2记录");
        jdbcTemplate.update("INSERT INTO \"" + TABLE + "\" (\"id\",\"tenant_id\",\"deleted\",\"name\") VALUES (?,?,1,?)",
                "r-deleted", 1L, "已删除记录");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + TABLE + "\"");
    }

    // ==================== 标识符 ====================

    @Test
    @DisplayName("表名/列名校验：合法通过，非法一律拒绝（映射不等于校验）")
    void identifierValidation() {
        assertTrue(DynamicTableSql.isValidTableName(TABLE));
        assertTrue(DynamicTableSql.isValidTableName(SUB_TABLE));
        assertFalse(DynamicTableSql.isValidTableName("sw_form_Abcdefghij"));
        assertFalse(DynamicTableSql.isValidTableName("sys_user"));
        assertFalse(DynamicTableSql.isValidTableName("sw_form_x\"; DROP TABLE t; --"));

        assertEquals("ref_target_id", DynamicTableSql.requireColumn("target", FieldType.REFERENCE));
        assertEquals("applicantNote", DynamicTableSql.requireColumn("applicantNote", FieldType.TEXT));

        // 映射结果必须再过校验：物理列名非法（保留字）即拒绝
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.requireColumn("select", FieldType.TEXT)).getCode());
        // 系统列不得当作业务列引用
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.requireColumn("tenant_id", FieldType.TEXT)).getCode());
        // 非法标识符不得进入 SQL 拼接
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class, () -> DynamicTableSql.quote("x\" OR 1=1")).getCode());
    }

    @Test
    @DisplayName("元数据表白名单：未登记表一律拒绝")
    void metadataTableAllowlist() {
        assertEquals("sw_form_config", DynamicTableSql.requireMetadataTable("sw_form_config"));
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.query(jdbcTemplate, "sys_user", "SELECT 1 FROM sys_user")).getCode());
    }

    // ==================== 强制谓词 ====================

    @Test
    @DisplayName("缺 tenant_id / deleted 条件的语句在执行前被拒绝")
    void mandatoryPredicatesEnforced() {
        String noTenant = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0";
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.query(jdbcTemplate, TABLE, noTenant)).getCode());

        String noDeleted = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"tenant_id\" = ?";
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.query(jdbcTemplate, TABLE, noDeleted, 1L)).getCode());

        String otherTable = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?"
                + " UNION ALL SELECT \"id\" FROM \"sw_form_zzzzzzzzzz\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?";
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.query(jdbcTemplate, TABLE, otherTable, 1L, 1L)).getCode());
    }

    @Test
    @DisplayName("参数绑定：占位符数量必须与参数一致（禁止值拼接）")
    void placeholderBindingEnforced() {
        String sql = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?";
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class, () -> DynamicTableSql.query(jdbcTemplate, TABLE, sql)).getCode());
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class, () -> DynamicTableSql.query(jdbcTemplate, TABLE, sql, 1L, 2L)).getCode());

        String withLiteral = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?"
                + " AND \"name\" LIKE ? ESCAPE '\\'";
        assertEquals(2, DynamicTableSql.countPlaceholders(withLiteral),
                "字符串字面量中的字符不得计入占位符");
        List<Map<String, Object>> rows = DynamicTableSql.query(jdbcTemplate, TABLE, withLiteral, 1L, "%租户%");
        assertEquals(1, rows.size(), "字面量含转义符的语句应可正常执行");
    }

    @Test
    @DisplayName("多语句与注释一律拒绝")
    void multiStatementRejected() {
        String sql = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?; "
                + "DROP TABLE \"" + TABLE + "\"";
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class, () -> DynamicTableSql.query(jdbcTemplate, TABLE, sql, 1L)).getCode());
        String commented = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 /* x */ AND \"tenant_id\" = ?";
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class, () -> DynamicTableSql.query(jdbcTemplate, TABLE, commented, 1L)).getCode());
    }

    @Test
    @DisplayName("租户条件强制：跨租户行读不到（谓词 + 参数）")
    void tenantPredicateScopesRows() {
        String sql = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?";
        assertEquals(1, DynamicTableSql.query(jdbcTemplate, TABLE, sql, 1L).size());
        assertEquals(1, DynamicTableSql.query(jdbcTemplate, TABLE, sql, 2L).size());
        assertEquals("r-t1", DynamicTableSql.query(jdbcTemplate, TABLE, sql, 1L).get(0).get("id"));
        // 租户上下文缺失即拒绝
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.appendLivePredicate(new StringBuilder(), new java.util.ArrayList<>(), null))
                        .getCode());
    }

    // ==================== 父行锁 ====================

    @Test
    @DisplayName("无事务上下文：退化为存在性检查且不持锁")
    void lockWithoutTransactionDegradesToExistenceCheck() {
        assertTrue(DynamicTableSql.tryLockLiveRow(jdbcTemplate, TABLE, "r-t1", 1L));
        assertFalse(DynamicTableSql.tryLockLiveRow(jdbcTemplate, TABLE, "r-deleted", 1L),
                "已软删记录不得判定为存活");
        assertFalse(DynamicTableSql.tryLockLiveRow(jdbcTemplate, TABLE, "r-t2", 1L),
                "跨租户记录不得判定为存活");
        assertFalse(DynamicTableSql.tryLockLiveRow(jdbcTemplate, TABLE, "not-exists", 1L));
        assertEquals(0, DynamicTableSql.registeredLockCount(), "无事务时不得登记长期持有的锁");
    }

    @Test
    @DisplayName("事务内：父行加锁并在事务结束后释放")
    void lockInsideTransactionHeldUntilCompletion() {
        Boolean live = txTemplate.execute(status -> {
            boolean locked = DynamicTableSql.tryLockLiveRow(jdbcTemplate, TABLE, "r-t1", 1L);
            assertEquals(1, DynamicTableSql.registeredLockCount(), "事务内应持有 1 把锁");
            return locked;
        });
        assertEquals(Boolean.TRUE, live);
        assertEquals(0, DynamicTableSql.registeredLockCount(), "事务结束后锁必须释放");
    }

    @Test
    @DisplayName("锁对象身份：非法表名/空 id 一律拒绝")
    void lockTargetValidation() {
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> new DynamicTableSql.LockTarget("bad_table", "r1")).getCode());
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> new DynamicTableSql.LockTarget(TABLE, " ")).getCode());
        assertEquals(FormErrorCode.DYNAMIC_SQL_CONTRACT_VIOLATION.getCode(),
                assertThrows(BaseException.class,
                        () -> DynamicTableSql.tryLockLiveRow(jdbcTemplate, TABLE, "r-t1", null)).getCode());
    }

    @Test
    @DisplayName("批量加锁按 (表名, 记录 id) 升序，结果与顺序无关")
    void batchLockIsOrderedAndStable() {
        List<DynamicTableSql.LockTarget> targets = List.of(
                new DynamicTableSql.LockTarget(TABLE, "r-t2"),
                new DynamicTableSql.LockTarget(TABLE, "r-t1"));
        Map<DynamicTableSql.LockTarget, Boolean> live =
                DynamicTableSql.tryLockLiveRows(jdbcTemplate, targets, 1L);
        assertEquals(2, live.size());
        assertTrue(live.get(new DynamicTableSql.LockTarget(TABLE, "r-t1")));
        assertFalse(live.get(new DynamicTableSql.LockTarget(TABLE, "r-t2")));
    }
}
