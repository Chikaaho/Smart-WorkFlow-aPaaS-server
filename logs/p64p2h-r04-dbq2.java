import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;

/** 参数化URL只读查询：<url> <outFile> <sql> */
public class p64p2h_r04_dbq2 {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        String out = args[1];
        String sql = args[2];
        StringBuilder sb = new StringBuilder();
        try (Connection c = DriverManager.getConnection(url, "postgres", "123456");
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= n; i++) line.append(rs.getObject(i) == null ? "NULL" : rs.getObject(i).toString()).append(i < n ? " | " : "");
                sb.append(line).append('\n');
            }
        }
        Files.write(Paths.get(out), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("WROTE " + out);
    }
}
