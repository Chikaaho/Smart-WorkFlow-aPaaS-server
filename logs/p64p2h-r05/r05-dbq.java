import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;

/** P64 回执05 只读SQL快照工具（UTF-8 直排文件）。用法: r05_dbq <outFile> <sql> */
public class r05_dbq {
    public static void main(String[] args) throws Exception {
        String out = args[0];
        String sql = args[1];
        StringBuilder sb = new StringBuilder();
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/smart_workflow_p64p2c?stringtype=unspecified",
                "postgres", "123456"); Statement st = c.createStatement()) {
            boolean hasResult = st.execute(sql);
            if (hasResult) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    StringBuilder head = new StringBuilder();
                    for (int i = 1; i <= n; i++) head.append(md.getColumnLabel(i)).append(i < n ? " | " : "");
                    sb.append(head).append("\n").append("-".repeat(Math.min(head.length(), 150))).append("\n");
                    int rows = 0;
                    while (rs.next()) {
                        StringBuilder line = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            Object v = rs.getObject(i);
                            String s = v == null ? "NULL" : String.valueOf(v);
                            if (s.length() > 500) s = s.substring(0, 500) + "…";
                            line.append(s).append(i < n ? " | " : "");
                        }
                        sb.append(line).append("\n");
                        rows++;
                        if (rows > 300) { sb.append("(truncated at 300 rows)\n"); break; }
                    }
                    sb.append("(").append(rows).append(" rows)\n");
                }
            } else {
                sb.append("updated=").append(st.getUpdateCount()).append("\n");
            }
        }
        Files.writeString(Path.of(out), sb.toString(), StandardCharsets.UTF_8);
        System.out.print(sb);
    }
}
