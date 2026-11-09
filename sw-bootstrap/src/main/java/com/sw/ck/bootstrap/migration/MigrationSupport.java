package com.sw.ck.bootstrap.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2 重复迁移使用的 Java 别名实现：H2 的 RENAME COLUMN 不支持 IF EXISTS。
 * 只接受迁移脚本内的固定标识符，不面向用户输入。
 */
public final class MigrationSupport {

    private MigrationSupport() {
    }

    public static void renameColumnIfExists(Connection connection, String table,
                                            String oldColumn, String newColumn) throws SQLException {
        if (columnExists(connection, table, oldColumn) && !columnExists(connection, table, newColumn)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " RENAME COLUMN " + oldColumn + " TO " + newColumn);
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }
}
