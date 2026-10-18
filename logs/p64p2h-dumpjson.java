import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;

/** 只读导出冻结图 triggers JSON 到文件（UTF-8 直排，不经控制台）。用法: p64p2h-dumpjson <graphVersion> <outFile> */
public class p64p2h_dumpjson {
    public static void main(String[] args) throws Exception {
        String ver = args[0];
        String out = args[1];
        String url = "jdbc:postgresql://127.0.0.1:5432/smart_workflow_p64p2c?stringtype=unspecified";
        try (Connection c = DriverManager.getConnection(url, "postgres", "123456");
             PreparedStatement ps = c.prepareStatement("SELECT graph_json::jsonb->'triggers' FROM sw_bpm_process_def_version WHERE def_id=2108817690357526529 AND graph_version=?")) {
            ps.setLong(1, Long.parseLong(ver));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String t = rs.getString(1);
                    Files.write(Paths.get(out), t == null ? new byte[0] : t.getBytes(StandardCharsets.UTF_8));
                    System.out.println("OK bytes=" + (t == null ? 0 : t.getBytes(StandardCharsets.UTF_8).length));
                } else {
                    System.out.println("NO ROW for graph_version=" + ver);
                }
            }
        }
    }
}
