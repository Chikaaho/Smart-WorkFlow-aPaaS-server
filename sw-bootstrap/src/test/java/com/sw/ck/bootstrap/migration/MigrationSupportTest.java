package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * comment → opinion_comment 改名保留数据的单元测试（H2）。
 */
class MigrationSupportTest {

    @Test
    @DisplayName("改名保留已有审批意见数据，重复执行幂等")
    void shouldRenameColumnAndKeepData() throws Exception {
        String url = "jdbc:h2:mem:migration_support_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sw_bpm_task_opinion_draft "
                        + "(id BIGINT PRIMARY KEY, comment VARCHAR(200))");
                statement.execute("INSERT INTO sw_bpm_task_opinion_draft (id, comment) VALUES (1, '保留意见')");
            }
            MigrationSupport.renameColumnIfExists(connection,
                    "SW_BPM_TASK_OPINION_DRAFT", "COMMENT", "OPINION_COMMENT");
            MigrationSupport.renameColumnIfExists(connection,
                    "SW_BPM_TASK_OPINION_DRAFT", "COMMENT", "OPINION_COMMENT");
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT opinion_comment FROM sw_bpm_task_opinion_draft WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("保留意见");
            }
        }
    }
}
