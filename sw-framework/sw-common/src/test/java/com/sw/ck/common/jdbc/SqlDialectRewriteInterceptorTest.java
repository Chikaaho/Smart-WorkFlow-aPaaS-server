package com.sw.ck.common.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行时 SQL 方言重写单元测试。
 */
class SqlDialectRewriteInterceptorTest {

    private final SqlDialectRewriteInterceptor interceptor = new SqlDialectRewriteInterceptor();

    @Test
    @DisplayName("PostgreSQL/MySQL/达梦等原生 LIMIT 不改写")
    void shouldKeepLimitForNativeVendors() {
        String sql = "SELECT * FROM sys_user WHERE deleted = 0 LIMIT 1";
        assertThat(interceptor.rewrite(sql, DatabaseVendor.POSTGRESQL)).isEqualTo(sql);
        assertThat(interceptor.rewrite(sql, DatabaseVendor.MYSQL)).isEqualTo(sql);
        assertThat(interceptor.rewrite(sql, DatabaseVendor.MARIADB)).isEqualTo(sql);
        assertThat(interceptor.rewrite(sql, DatabaseVendor.DM)).isEqualTo(sql);
        assertThat(interceptor.rewrite(sql, DatabaseVendor.KINGBASE)).isEqualTo(sql);
        assertThat(interceptor.rewrite(sql, DatabaseVendor.OPENGAUSS)).isEqualTo(sql);
    }

    @Test
    @DisplayName("Oracle 尾部 LIMIT 改 FETCH FIRST / OFFSET FETCH")
    void shouldRewriteLimitForOracle() {
        assertThat(interceptor.rewrite("SELECT * FROM sys_user LIMIT 1", DatabaseVendor.ORACLE))
                .isEqualTo("SELECT * FROM sys_user FETCH FIRST 1 ROWS ONLY");
        assertThat(interceptor.rewrite("SELECT * FROM sys_user LIMIT 10 OFFSET 20", DatabaseVendor.ORACLE))
                .isEqualTo("SELECT * FROM sys_user OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY");
        assertThat(interceptor.rewrite("select * from sys_user limit 1;", DatabaseVendor.ORACLE))
                .isEqualTo("select * from sys_user FETCH FIRST 1 ROWS ONLY");
    }

    @Test
    @DisplayName("Oracle FOR UPDATE + LIMIT 直接报错")
    void shouldRejectOracleForUpdateWithLimit() {
        assertThatThrownBy(() -> interceptor.rewrite(
                "SELECT * FROM sys_tenant WHERE id = ? FOR UPDATE LIMIT 1", DatabaseVendor.ORACLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOR UPDATE");
    }

    @Test
    @DisplayName("SQL Server 尾部 LIMIT 改 OFFSET FETCH；无 ORDER BY 时补 ORDER BY")
    void shouldRewriteLimitForSqlServer() {
        assertThat(interceptor.rewrite("SELECT * FROM sys_user ORDER BY id LIMIT 1", DatabaseVendor.SQL_SERVER))
                .isEqualTo("SELECT * FROM sys_user ORDER BY id OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY");
        assertThat(interceptor.rewrite("SELECT * FROM sys_user LIMIT 10 OFFSET 20", DatabaseVendor.SQL_SERVER))
                .isEqualTo("SELECT * FROM sys_user ORDER BY (SELECT NULL) OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY");
        assertThat(interceptor.rewrite("SELECT * FROM sys_user LIMIT ?", DatabaseVendor.SQL_SERVER))
                .isEqualTo("SELECT * FROM sys_user ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
    }

    @Test
    @DisplayName("SQL Server FOR UPDATE 改成表提示，SKIP LOCKED 加 READPAST")
    void shouldRewriteSqlServerForUpdate() {
        assertThat(interceptor.rewrite(
                "SELECT id FROM sw_bpm_command WHERE id = ? FOR UPDATE", DatabaseVendor.SQL_SERVER))
                .isEqualTo("SELECT id FROM sw_bpm_command WITH (UPDLOCK, ROWLOCK) WHERE id = ?");
        assertThat(interceptor.rewrite(
                "SELECT id FROM sw_bpm_resource_usage WHERE segment = ? FOR UPDATE SKIP LOCKED",
                DatabaseVendor.SQL_SERVER))
                .isEqualTo("SELECT id FROM sw_bpm_resource_usage WITH (UPDLOCK, ROWLOCK, READPAST) WHERE segment = ?");
    }

    @Test
    @DisplayName("MySQL/PG 的 FOR UPDATE 保持原样")
    void shouldKeepForUpdateForNativeVendors() {
        String sql = "SELECT id FROM sw_bpm_command WHERE id = ? FOR UPDATE";
        assertThat(interceptor.rewrite(sql, DatabaseVendor.POSTGRESQL)).isEqualTo(sql);
        assertThat(interceptor.rewrite(sql, DatabaseVendor.MYSQL)).isEqualTo(sql);
    }

    @Test
    @DisplayName("字符串/注释里的 LIMIT 不参与改写")
    void shouldIgnoreLimitInsideLiteralOrComment() {
        String sql = "SELECT 'LIMIT 1' AS note FROM sys_user -- LIMIT 2";
        assertThat(interceptor.rewrite(sql, DatabaseVendor.ORACLE)).isEqualTo(sql);
    }

    @Test
    @DisplayName("非顶层 LIMIT 在 Oracle/SQL Server 上报错")
    void shouldRejectNestedLimit() {
        String sql = "SELECT * FROM (SELECT * FROM sys_user LIMIT 1) t WHERE deleted = 0";
        assertThatThrownBy(() -> interceptor.rewrite(sql, DatabaseVendor.ORACLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIMIT");
        assertThatThrownBy(() -> interceptor.rewrite(sql, DatabaseVendor.SQL_SERVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIMIT");
    }

    @Test
    @DisplayName("字符串字面量掩码保留等长位置")
    void shouldMaskLiteralsAndComments() {
        String original = "SELECT 'a''b' AS x, \"c\"\"d\" FROM t -- comment\nWHERE x = 1";
        String masked = SqlDialectRewriteInterceptor.maskLiteralsAndComments(original);
        assertThat(masked).hasSameSizeAs(original);
        assertThat(masked).contains("SELECT").contains("FROM t").contains("WHERE x = 1");
        assertThat(masked).doesNotContain("comment").doesNotContain("a''b");
    }
}
