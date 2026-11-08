package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2：MySQL 5.7 资源段探测的会话变量绑定在同一个连接上，设置后在 finally 还原。
 */
class ResourceRowLockProbeTest {

    @Test
    @DisplayName("探测成功：同一连接设置 1 秒并在结束后还原原值")
    void restoresSessionTimeoutOnSameConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement session = mock(Statement.class);
        ResultSet previous = mock(ResultSet.class);
        PreparedStatement probe = mock(PreparedStatement.class);
        ResultSet probeRows = mock(ResultSet.class);

        when(connection.createStatement()).thenReturn(session);
        when(session.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")).thenReturn(previous);
        when(previous.next()).thenReturn(true);
        when(previous.getInt(1)).thenReturn(50);
        when(connection.prepareStatement(contains("FOR UPDATE"))).thenReturn(probe);
        when(probe.executeQuery()).thenReturn(probeRows);
        when(probeRows.next()).thenReturn(true);
        when(probeRows.getLong(1)).thenReturn(7L);

        ResourceRowLockProbe subject = new ResourceRowLockProbe(
                mock(BpmResourceUsageMapper.class), mock(JdbcTemplate.class));
        Long id = subject.probeMySqlOnConnection(connection, "GLOBAL", 0L, "SHARED");

        assertEquals(7L, id);
        verify(probe).setQueryTimeout(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(session, times(2)).execute(sql.capture());
        assertEquals(List.of("SET SESSION innodb_lock_wait_timeout = 1",
                "SET SESSION innodb_lock_wait_timeout = 50"), sql.getAllValues());
    }

    @Test
    @DisplayName("锁等待超时：返回 null，仍还原原超时值")
    void restoresSessionTimeoutWhenBusy() throws Exception {
        Connection connection = mock(Connection.class);
        Statement session = mock(Statement.class);
        ResultSet previous = mock(ResultSet.class);
        PreparedStatement probe = mock(PreparedStatement.class);

        when(connection.createStatement()).thenReturn(session);
        when(session.executeQuery(anyString())).thenReturn(previous);
        when(previous.next()).thenReturn(true);
        when(previous.getInt(1)).thenReturn(50);
        when(connection.prepareStatement(contains("FOR UPDATE"))).thenReturn(probe);
        when(probe.executeQuery()).thenThrow(new SQLTimeoutException("lock wait timeout"));

        ResourceRowLockProbe subject = new ResourceRowLockProbe(
                mock(BpmResourceUsageMapper.class), mock(JdbcTemplate.class));
        assertNull(subject.probeMySqlOnConnection(connection, "GLOBAL", 0L, "SHARED"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(session, times(2)).execute(sql.capture());
        assertEquals("SET SESSION innodb_lock_wait_timeout = 50", sql.getAllValues().get(1));
    }
}
