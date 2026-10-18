import java.sql.*;

/** 创建 P64 阶段Ⅱ 验证库（新库名，避免破坏旧验证库历史）。 */
public class p64p2_createdb {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:5432/postgres?stringtype=unspecified";
        String user = System.getenv("PG_USERNAME") == null ? "postgres" : System.getenv("PG_USERNAME");
        String pass = System.getenv("PG_PASSWORD") == null ? "123456" : System.getenv("PG_PASSWORD");
        String db = args.length > 0 ? args[0] : "smart_workflow_p64p2c";
        try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("select 1 from pg_database where datname = '" + db + "'")) {
                if (rs.next()) {
                    System.out.println("database exists: " + db);
                    return;
                }
            }
            st.execute("create database " + db);
            System.out.println("database created: " + db);
        }
    }
}
