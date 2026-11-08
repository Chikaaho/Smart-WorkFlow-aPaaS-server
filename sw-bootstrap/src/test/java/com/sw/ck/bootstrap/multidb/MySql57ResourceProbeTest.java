package com.sw.ck.bootstrap.multidb;

import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.bpm.process.service.ResourceRowLockProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R2：真实 MySQL 5.7 的资源段探测与原子领取并发验证（未配置 {@code mysql.url} 时跳过）。
 * <p>
 * 覆盖：行被持有时探测在 1 秒上限内返回 null（不无限阻塞）；行空闲时返回 id；
 * 8 线程原子条件占用恰好 1 个成功（不重复领取）。测试使用独立临时库
 * {@code swmdb_probe}，不触碰应用库。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MySQL 5.7 资源段探测：锁等待上限与原子领取（真实库；未配置 mysql.url 时跳过）")
class MySql57ResourceProbeTest {

    private static final String DATABASE = "swmdb_probe";
    private static final Pattern JDBC_URL = Pattern.compile("^(jdbc:mysql://[^/]+)/([^?]*)(\\?.*)?$");

    private String serverUrl;
    private String targetUrl;
    private String user;
    private String password;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ResourceRowLockProbe probe;

    @BeforeAll
    void setUp() throws Exception {
        String url = System.getProperty("mysql.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 mysql.url，跳过 MySQL 5.7 资源探测测试");
        user = System.getProperty("mysql.user", "root");
        password = System.getProperty("mysql.password", "");
        Matcher matcher = JDBC_URL.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("mysql.url 应形如 jdbc:mysql://host:port/db: " + url);
        }
        String params = matcher.group(3) == null ? "" : matcher.group(3);
        serverUrl = matcher.group(1) + "/mysql" + params;
        targetUrl = matcher.group(1) + "/" + DATABASE + params;

        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
            statement.executeUpdate("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        try (Connection connection = DriverManager.getConnection(targetUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE sw_bpm_resource_usage ("
                    + "id BIGINT NOT NULL PRIMARY KEY,"
                    + "tenant_id BIGINT NOT NULL DEFAULT 0,"
                    + "scope VARCHAR(64) NOT NULL,"
                    + "scope_key BIGINT NOT NULL,"
                    + "segment VARCHAR(64) NOT NULL,"
                    + "outstanding BIGINT NOT NULL DEFAULT 0,"
                    + "version BIGINT NOT NULL DEFAULT 0,"
                    + "deleted SMALLINT NOT NULL DEFAULT 0,"
                    + "create_time TIMESTAMP NULL DEFAULT NULL,"
                    + "create_by BIGINT,"
                    + "update_time TIMESTAMP NULL DEFAULT NULL,"
                    + "update_by BIGINT"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
            statement.executeUpdate("INSERT INTO sw_bpm_resource_usage "
                    + "(id, scope, scope_key, segment, outstanding) VALUES (1, 'GLOBAL', 0, 'SHARED', 0)");
        }
        dataSource = new DriverManagerDataSource(targetUrl, user, password);
        jdbc = new JdbcTemplate(dataSource);
        probe = new ResourceRowLockProbe(mock(BpmResourceUsageMapper.class), jdbc);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (serverUrl == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    @DisplayName("行被其他事务持有：探测在 1 秒上限内返回 null，不无限阻塞")
    void probeReturnsNullWithinBoundWhenRowLocked() throws Exception {
        try (Connection holder = DriverManager.getConnection(targetUrl, user, password)) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id FROM sw_bpm_resource_usage WHERE scope = ? AND scope_key = ? "
                            + "AND segment = ? FOR UPDATE")) {
                statement.setString(1, "GLOBAL");
                statement.setLong(2, 0L);
                statement.setString(3, "SHARED");
                try (ResultSet rs = statement.executeQuery()) {
                    assertTrue(rs.next());
                }
            }
            long start = System.nanoTime();
            Long id = probe.probe("GLOBAL", 0L, "SHARED");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertNull(id, "段被持有时应返回 null");
            assertTrue(elapsedMillis < 3_000, "探测等待上限应约 1 秒，实际 " + elapsedMillis + "ms");
            holder.rollback();
        }
    }

    @Test
    @DisplayName("行空闲：探测返回 id")
    void probeReturnsIdWhenRowFree() {
        assertEquals(1L, probe.probe("GLOBAL", 0L, "SHARED"));
    }

    @Test
    @DisplayName("8 线程原子条件占用：恰好 1 个成功，outstanding 不超上限")
    void concurrentAtomicClaimOnlyOneSucceeds() throws Exception {
        jdbc.update("UPDATE sw_bpm_resource_usage SET outstanding = 0, version = 0 WHERE id = 1");
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try (Connection connection = DriverManager.getConnection(targetUrl, user, password);
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE sw_bpm_resource_usage SET outstanding = outstanding + 1, "
                                     + "version = version + 1 WHERE scope = ? AND scope_key = ? "
                                     + "AND segment = ? AND outstanding + 1 <= ?")) {
                    statement.setString(1, "GLOBAL");
                    statement.setLong(2, 0L);
                    statement.setString(3, "SHARED");
                    statement.setLong(4, 1L);
                    if (statement.executeUpdate() == 1) {
                        successes.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, successes.get(), "上限 1 时只能有 1 个线程领取成功");
        Long outstanding = jdbc.queryForObject(
                "SELECT outstanding FROM sw_bpm_resource_usage WHERE id = 1", Long.class);
        assertEquals(1L, outstanding, "最终占用应恰好为 1");
    }
}
