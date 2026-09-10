package com.sw.ck.bpm.engine.executor;

import com.sw.ck.bpm.engine.config.ExternalDatasourceProperties;
import com.sw.ck.bpm.engine.datasource.ExternalDatasourceManager;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.bpm.engine.service.SqlExecutionAuditService;
import com.sw.ck.form.api.exception.ExternalDatasourceResultLimitExceededException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("外部数据源执行器·结果上限")
class SqlExecutorTest {

    private final ExternalDatasourceService datasourceService = mock(ExternalDatasourceService.class);
    private final ExternalDatasourceManager poolManager = mock(ExternalDatasourceManager.class);
    private final SqlExecutionAuditService auditService = mock(SqlExecutionAuditService.class);
    private final HikariDataSource dataSource = new HikariDataSource();
    private SqlExecutor executor;

    @BeforeEach
    void setUp() {
        dataSource.setJdbcUrl("jdbc:h2:mem:sql_executor_limit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE IF NOT EXISTS ext_rows (id INT PRIMARY KEY)");
        jdbc.update("DELETE FROM ext_rows");
        jdbc.update("INSERT INTO ext_rows(id) VALUES (1), (2), (3)");

        ExternalDatasource entity = new ExternalDatasource();
        entity.setId(9L);
        entity.setName("controlled-h2");
        entity.setEnabled(1);
        when(datasourceService.getById(9L)).thenReturn(entity);
        when(poolManager.getOrCreatePool(any(ExternalDatasource.class))).thenReturn(dataSource);

        ExternalDatasourceProperties properties = new ExternalDatasourceProperties();
        ExternalDatasourceProperties.Execution execution = new ExternalDatasourceProperties.Execution();
        execution.setMaxRows(2);
        execution.setQueryTimeout(5);
        properties.setExecution(execution);
        executor = new SqlExecutor(datasourceService, poolManager, auditService, properties);
    }

    @AfterEach
    void tearDown() {
        new JdbcTemplate(dataSource).execute("DROP TABLE IF EXISTS ext_rows");
        dataSource.close();
    }

    @Test
    @DisplayName("超过 maxRows 明确失败且只记录失败审计，不返回伪完整行集")
    void execute_shouldRejectOverLimit() {
        assertThatThrownBy(() -> executor.execute(9L,
                "SELECT id FROM ext_rows ORDER BY id", 7L, "tester"))
                .isInstanceOf(ExternalDatasourceResultLimitExceededException.class)
                .hasMessageContaining("maxRows=2");

        verify(auditService).auditFailure(org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq("controlled-h2"),
                org.mockito.ArgumentMatchers.eq("SELECT id FROM ext_rows ORDER BY id"), anyLong(), anyString(),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("tester"));
        verify(auditService, never()).auditSuccess(any(), any(), any(), anyInt(),
                anyLong(), any(), any());
    }
}
