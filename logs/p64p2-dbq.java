import java.nio.file.*;
import java.sql.*;

/** P64 阶段Ⅱ 实机验证用只读/受控 SQL 工具：java --source 21 p64p2-dbq.java <sqlfile|sql> [--write] */
public class p64p2_dbq {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:5432/smart_workflow_p64p2?stringtype=unspecified";
        String user = System.getenv("PG_USERNAME") == null ? "postgres" : System.getenv("PG_USERNAME");
        String pass = System.getenv("PG_PASSWORD") == null ? "123456" : System.getenv("PG_PASSWORD");
        if (args.length < 1) {
            System.out.println("usage: <sql|sqlfile>");
            return;
        }
        String sql = args[0];
        if (sql.endsWith(".sql")) {
            Path p = Paths.get(sql);
            if (Files.exists(p)) {
                sql = Files.readString(p);
            }
        }
        try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
            boolean hasResult = st.execute(sql);
            if (hasResult) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    StringBuilder head = new StringBuilder();
                    for (int i = 1; i <= n; i++) head.append(md.getColumnLabel(i)).append(i < n ? " | " : "");
                    System.out.println(head);
                    System.out.println("-".repeat(Math.min(head.length(), 120)));
                    int rows = 0;
                    while (rs.next()) {
                        StringBuilder line = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            Object v = rs.getObject(i);
                            String s = v == null ? "NULL" : String.valueOf(v);
                            if (s.length() > 300) s = s.substring(0, 300) + "…";
                            line.append(s).append(i < n ? " | " : "");
                        }
                        System.out.println(line);
                        rows++;
                        if (rows > 200) { System.out.println("(truncated at 200 rows)"); break; }
                    }
                    System.out.println("(" + rows + " rows)");
                }
            } else {
                System.out.println("updated=" + st.getUpdateCount());
            }
        }
    }
}
