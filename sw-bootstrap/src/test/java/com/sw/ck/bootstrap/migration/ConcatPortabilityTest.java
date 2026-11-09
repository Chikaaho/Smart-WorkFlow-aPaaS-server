package com.sw.ck.bootstrap.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 三参数 CONCAT 改写后的 H2 / PostgreSQL 语义验证。
 * <p>
 * B1 把 15 处 {@code CONCAT('%', #{p}, '%')} 改为嵌套
 * {@code CONCAT(CONCAT('%', #{p}), '%')}（Oracle 的 CONCAT 只接受两个参数），
 * 并把 {@code CAST(... AS VARCHAR)} 改为 {@code CONCAT('', id)}。本测试在 H2 与真实
 * PostgreSQL 上执行改写后的表达式，证明 LIKE 通配符包裹结果与整数转文本结果不变。
 * </p>
 */
@DisplayName("嵌套 CONCAT / CONCAT('', bigint) 的 H2 与 PostgreSQL 语义")
class ConcatPortabilityTest {

    @Test
    @DisplayName("H2：嵌套 CONCAT 包裹 LIKE 通配符，空串+整数转文本")
    void h2() throws Exception {
        String url = "jdbc:h2:mem:concat_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        assertPortableConcat(url, "sa", "");
    }

    @Test
    @DisplayName("PostgreSQL：嵌套 CONCAT 包裹 LIKE 通配符，空串+整数转文本")
    void postgres() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            assertPortableConcat(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
        }
    }

    private static void assertPortableConcat(String url, String user, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT CONCAT(CONCAT('%', ?), '%'), CONCAT('', CAST(? AS BIGINT))")) {
            statement.setString(1, "abc");
            statement.setLong(2, 42L);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals("%abc%", rs.getString(1), "嵌套 CONCAT 应保留 LIKE 通配符包裹语义");
                assertEquals("42", rs.getString(2), "CONCAT('', bigint) 应等价于原 CAST 转文本");
            }
        }
    }
}
