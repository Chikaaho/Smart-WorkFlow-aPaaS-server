package com.sw.ck.common.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库厂商识别与方言能力的纯单元测试。
 */
class DatabaseVendorTest {

    @Test
    @DisplayName("JDBC URL 前缀识别全部目标数据库")
    void shouldDetectVendorFromJdbcUrl() {
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:h2:mem:test")).isEqualTo(DatabaseVendor.H2);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:postgresql://localhost/db")).isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:mysql://localhost/db")).isEqualTo(DatabaseVendor.MYSQL);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:mariadb://localhost/db")).isEqualTo(DatabaseVendor.MARIADB);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521/xe")).isEqualTo(DatabaseVendor.ORACLE);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:sqlserver://localhost;databaseName=db")).isEqualTo(DatabaseVendor.SQL_SERVER);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:dm://localhost:5236")).isEqualTo(DatabaseVendor.DM);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:kingbase8://localhost:54321/db")).isEqualTo(DatabaseVendor.KINGBASE);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:opengauss://localhost:5432/db")).isEqualTo(DatabaseVendor.OPENGAUSS);
        assertThat(DatabaseVendor.fromJdbcUrl("jdbc:unknown://localhost/db")).isNull();
    }

    @Test
    @DisplayName("产品名识别覆盖达梦/金仓/openGauss/大小写变体")
    void shouldDetectVendorFromProductName() {
        assertThat(DatabaseVendor.fromProductName("DM DBMS")).isEqualTo(DatabaseVendor.DM);
        assertThat(DatabaseVendor.fromProductName("KingbaseES")).isEqualTo(DatabaseVendor.KINGBASE);
        assertThat(DatabaseVendor.fromProductName("openGauss")).isEqualTo(DatabaseVendor.OPENGAUSS);
        assertThat(DatabaseVendor.fromProductName("GaussDB")).isEqualTo(DatabaseVendor.OPENGAUSS);
        assertThat(DatabaseVendor.fromProductName("Microsoft SQL Server")).isEqualTo(DatabaseVendor.SQL_SERVER);
        assertThat(DatabaseVendor.fromProductName("MariaDB")).isEqualTo(DatabaseVendor.MARIADB);
        assertThat(DatabaseVendor.fromProductName("SomethingElse")).isNull();
    }

    @Test
    @DisplayName("标识符引用按方言选择")
    void shouldWrapIdentifier() {
        assertThat(DatabaseVendor.POSTGRESQL.wrapIdentifier("tenant_id")).isEqualTo("\"tenant_id\"");
        assertThat(DatabaseVendor.ORACLE.wrapIdentifier("tenant_id")).isEqualTo("\"tenant_id\"");
        assertThat(DatabaseVendor.MYSQL.wrapIdentifier("tenant_id")).isEqualTo("`tenant_id`");
        assertThat(DatabaseVendor.MARIADB.wrapIdentifier("tenant_id")).isEqualTo("`tenant_id`");
        assertThat(DatabaseVendor.SQL_SERVER.wrapIdentifier("tenant_id")).isEqualTo("[tenant_id]");
    }

    @Test
    @DisplayName("LIMIT 子句按方言生成")
    void shouldBuildLimitClause() {
        assertThat(DatabaseVendor.POSTGRESQL.limitClause(10)).isEqualTo(" LIMIT 10");
        assertThat(DatabaseVendor.MYSQL.limitClause(10)).isEqualTo(" LIMIT 10");
        assertThat(DatabaseVendor.ORACLE.limitClause(10)).isEqualTo(" FETCH FIRST 10 ROWS ONLY");
        assertThat(DatabaseVendor.SQL_SERVER.limitClause(10))
                .isEqualTo(" OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY");
        assertThat(DatabaseVendor.ORACLE.limitOffsetClause(10, 20))
                .isEqualTo(" OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY");
        assertThat(DatabaseVendor.POSTGRESQL.limitOffsetClause(10, 20))
                .isEqualTo(" LIMIT 10 OFFSET 20");
        assertThatThrownBy(() -> DatabaseVendor.POSTGRESQL.limitClause(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SKIP LOCKED 支持矩阵")
    void shouldReportSkipLockedSupport() {
        assertThat(DatabaseVendor.POSTGRESQL.supportsSkipLocked()).isTrue();
        assertThat(DatabaseVendor.MYSQL.supportsSkipLocked()).isTrue();
        assertThat(DatabaseVendor.ORACLE.supportsSkipLocked()).isTrue();
        assertThat(DatabaseVendor.KINGBASE.supportsSkipLocked()).isTrue();
        assertThat(DatabaseVendor.OPENGAUSS.supportsSkipLocked()).isTrue();
        assertThat(DatabaseVendor.MARIADB.supportsSkipLocked()).isTrue();
        assertThat(DatabaseVendor.SQL_SERVER.supportsSkipLocked()).isFalse();
        assertThat(DatabaseVendor.DM.supportsSkipLocked()).isFalse();
    }

    @Test
    @DisplayName("动态宽表类型映射")
    void shouldMapFormTypes() {
        assertThat(DatabaseVendor.ORACLE.formTextType(1000)).isEqualTo("VARCHAR2(1000 CHAR)");
        assertThat(DatabaseVendor.SQL_SERVER.formTextType(1000)).isEqualTo("NVARCHAR(1000)");
        assertThat(DatabaseVendor.MYSQL.formLongTextType()).isEqualTo("LONGTEXT");
        assertThat(DatabaseVendor.ORACLE.formLongTextType()).isEqualTo("CLOB");
        assertThat(DatabaseVendor.SQL_SERVER.formLongTextType()).isEqualTo("NVARCHAR(MAX)");
        assertThat(DatabaseVendor.ORACLE.formBooleanType()).isEqualTo("NUMBER(1)");
        assertThat(DatabaseVendor.SQL_SERVER.formBooleanType()).isEqualTo("BIT");
    }
}
