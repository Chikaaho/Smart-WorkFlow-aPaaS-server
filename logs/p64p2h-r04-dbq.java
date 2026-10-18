import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;

/** P64 回执04 只读SQL快照工具（UTF-8 直排文件，不经控制台）。用法: p64p2h_r04_dbq <outFile> <sql> */
public class p64p2h_r04_dbq {
    public static void main(String[] args) throws Exception {
        String out = args[0];
        String sql = args[1];
        StringBuilder sb = new StringBuilder();
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/smart_workflow_p64p2c?stringtype=unspecified",
                "postgres", "123456");
             Statement st = c.createStatement()) {
            boolean hasResult = st.execute(sql);
            if (!hasResult) {
                sb.append("(no result set, updateCount=").append(st.getUpdateCount()).append(")\n");
            } else {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    StringBuilder head = new StringBuilder();
                    for (int i = 1; i <= n; i++) head.append(md.getColumnLabel(i)).append(i < n ? " | " : "");
                    sb.append(head).append('\n');
                    int rows = 0;
                    while (rs.next()) {
                        StringBuilder line = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            Object v = rs.getObject(i);
                            line.append(v == null ? "NULL" : v.toString()).append(i < n ? " | " : "");
                        }
                        sb.append(line).append('\n');
                        rows++;
                    }
                    sb.append("(").append(rows).append(" rows)\n");
                }
            }
        }
        Files.write(Paths.get(out), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("WROTE " + out);
    }
}
