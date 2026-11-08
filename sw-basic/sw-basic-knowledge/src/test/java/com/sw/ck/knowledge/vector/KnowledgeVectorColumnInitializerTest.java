package com.sw.ck.knowledge.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("F1 阶段 3：pgvector 向量列补建")
class KnowledgeVectorColumnInitializerTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final KnowledgeVectorColumnInitializer initializer =
            new KnowledgeVectorColumnInitializer(jdbcTemplate);
    private DatabaseMetaData metaData;

    @BeforeEach
    void stubPostgresqlVendor() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        metaData = mock(DatabaseMetaData.class);
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn("jdbc:postgresql://localhost:5432/test");
    }

    @Test
    @DisplayName("扩展已安装且列缺失 → 自动 ALTER TABLE 补建")
    void backfillsColumnWhenExtensionInstalled() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1, 0);
        initializer.run(null);
        verify(jdbcTemplate).execute("ALTER TABLE sw_knowledge_chunk ADD COLUMN embedding vector");
    }

    @Test
    @DisplayName("扩展未安装 → 不执行 ALTER（启动不阻断，首次写入明确报错）")
    void skipsWhenExtensionMissing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 0);
        initializer.run(null);
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("列已存在 → 幂等不重复 ALTER")
    void skipsWhenColumnExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1, 1);
        initializer.run(null);
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("非 PostgreSQL → 跳过 pgvector 补建（非 PG 用 database 向量存储）")
    void skipsOnNonPostgresql() throws Exception {
        when(metaData.getURL()).thenReturn("jdbc:mysql://localhost:3306/test");
        initializer.run(null);
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class));
        verify(jdbcTemplate, never()).execute(anyString());
    }
}
