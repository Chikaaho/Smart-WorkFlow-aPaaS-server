package com.sw.ck.bpm.engine.integration;

import com.sw.ck.bpm.engine.config.ExternalDatasourceProperties;
import com.sw.ck.bpm.engine.datasource.ExternalDatasourceManager;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.bpm.engine.service.SqlExecutionAuditService;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.form.api.dto.ExtQueryResult;
import com.sw.ck.form.api.exception.ExternalDatasourceResultLimitExceededException;
import com.sw.ck.form.api.port.ExtDatasourceQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受控外部数据源真实执行链集成测试（J020 受控集成证据，F020 运行侧）。
 * <p>
 * 全链使用真实组件：真实 AES-256-GCM 加密、真实 Hikari 独立连接池、真实 SqlExecutor
 * （jsqlparser SELECT-only 校验 + 行数上限 + 超时）、真实 form 端口适配；仅数据源
 * 登记解析与审计落库用桩替代（登记路径由控制器测试与 G2 测试覆盖）。
 * 外部库为独立内存 H2（模拟外部数据库），与主库隔离。
 * </p>
 */
class FormExtDatasourceControlledChainTest {

    private static final String EXT_URL =
            "jdbc:h2:mem:ext_controlled_chain;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    private final AesGcmCipher cipher = new AesGcmCipher(
            Base64.getEncoder().encodeToString(new byte[]{
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
    private final ExternalDatasourceService datasourceService = mock(ExternalDatasourceService.class);
    private final SqlExecutionAuditService auditService = mock(SqlExecutionAuditService.class);
    private ExternalDatasourceManager poolManager;
    private SqlExecutor sqlExecutor;
    private ExtDatasourceQueryPort adapter;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(EXT_URL, "ext_user", "ext-pass-020");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS ext_obj");
            stmt.execute("CREATE TABLE ext_obj (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("INSERT INTO ext_obj VALUES ('V-1', '对象一'), ('V-2', '对象二')");
        }
        poolManager = new ExternalDatasourceManager(cipher, properties(1000));
        sqlExecutor = new SqlExecutor(datasourceService, poolManager, auditService, properties(1000));
        adapter = new com.sw.ck.bpm.engine.adapter.FormExtDatasourceQueryAdapter(sqlExecutor);
    }

    @AfterEach
    void tearDown() {
        if (poolManager != null) {
            poolManager.shutdown();
        }
    }

    private static ExternalDatasourceProperties properties(int maxRows) {
        ExternalDatasourceProperties properties = new ExternalDatasourceProperties();
        properties.setCipherKey(Base64.getEncoder().encodeToString(new byte[32]));
        properties.getExecution().setMaxRows(maxRows);
        properties.getExecution().setQueryTimeout(10);
        return properties;
    }

    private ExternalDatasource datasource(Long id, boolean enabled) {
        ExternalDatasource entity = new ExternalDatasource();
        entity.setId(id);
        entity.setName("报表库");
        entity.setType("h2");
        entity.setDriverClass("org.h2.Driver");
        entity.setJdbcUrl(EXT_URL);
        entity.setUsername("ext_user");
        entity.setPasswordCipher(cipher.encrypt("ext-pass-020"));
        entity.setReadOnly(1);
        entity.setEnabled(enabled ? 1 : 0);
        return entity;
    }

    @Test
    @DisplayName("真实受控链：密文解密建池、只读 SELECT 返回契约行并留成功审计")
    void realChainReturnsRowsAndAuditsSuccess() {
        when(datasourceService.getById(9L)).thenReturn(datasource(9L, true));

        Optional<ExtQueryResult> result = adapter.executeQuery(
                9L, "SELECT id AS \"value\", name AS \"display\" FROM ext_obj ORDER BY id", 42L, "operator");

        assertThat(result).isPresent();
        assertThat(result.get().getRowCount()).isEqualTo(2);
        assertThat(result.get().getRows().get(0))
                .containsEntry("value", "V-1")
                .containsEntry("display", "对象一");
        verify(auditService).auditSuccess(eq(9L), eq("报表库"),
                contains("SELECT id AS"), eq(2), anyLong(), eq(42L), eq("operator"));
    }

    @Test
    @DisplayName("真实受控链：写语句被 SELECT-only 校验拒绝且零副作用、不留执行审计")
    void realChainRejectsWriteAndAuditsFailure() {
        when(datasourceService.getById(9L)).thenReturn(datasource(9L, true));

        assertThatThrownBy(() -> adapter.executeQuery(
                9L, "INSERT INTO ext_obj VALUES ('V-9', '越权写入')", 42L, "operator"))
                .isInstanceOf(IllegalArgumentException.class);

        try (Connection conn = DriverManager.getConnection(EXT_URL, "ext_user", "ext-pass-020");
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM ext_obj")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // 写语句在校验阶段即被拒绝，未进入执行与审计路径
        verify(auditService, never()).auditFailure(anyLong(), anyString(), anyString(),
                anyLong(), anyString(), anyLong(), anyString());
        verify(auditService, never()).auditSuccess(anyLong(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("真实受控链：执行期失败留失败审计（表不存在）")
    void realChainAuditsExecutionFailure() {
        when(datasourceService.getById(9L)).thenReturn(datasource(9L, true));

        assertThatThrownBy(() -> adapter.executeQuery(
                9L, "SELECT id FROM ext_missing", 42L, "operator"))
                .isInstanceOf(RuntimeException.class);

        verify(auditService).auditFailure(eq(9L), eq("报表库"),
                contains("ext_missing"), anyLong(), anyString(), eq(42L), eq("operator"));
        verify(auditService, never()).auditSuccess(anyLong(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("真实受控链：停用/未登记数据源以目标缺失表达（empty），查询未执行")
    void realChainTreatsUnavailableDatasourceAsEmpty() {
        when(datasourceService.getById(9L)).thenReturn(datasource(9L, false));
        assertThat(adapter.executeQuery(9L, "SELECT 1 FROM ext_obj", 42L, "operator")).isEmpty();

        when(datasourceService.getById(404L)).thenReturn(null);
        assertThat(adapter.executeQuery(404L, "SELECT 1 FROM ext_obj", 42L, "operator")).isEmpty();

        verify(auditService, never()).auditSuccess(anyLong(), anyString(), anyString(),
                anyInt(), anyLong(), anyLong(), anyString());
        verify(auditService, never()).auditFailure(anyLong(), anyString(), anyString(),
                anyLong(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("真实受控链：超过行数上限明确失败，不返回不完整行集")
    void realChainRejectsOverLimitResult() {
        when(datasourceService.getById(9L)).thenReturn(datasource(9L, true));
        SqlExecutor limitExecutor = new SqlExecutor(datasourceService, poolManager, auditService,
                properties(1));
        ExtDatasourceQueryPort limitAdapter =
                new com.sw.ck.bpm.engine.adapter.FormExtDatasourceQueryAdapter(limitExecutor);

        assertThatThrownBy(() -> limitAdapter.executeQuery(
                9L, "SELECT id AS \"value\", name AS \"display\" FROM ext_obj", 42L, "operator"))
                .isInstanceOf(ExternalDatasourceResultLimitExceededException.class);
    }
}
