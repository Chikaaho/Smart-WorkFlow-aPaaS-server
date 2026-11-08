package com.sw.ck.form.handler;

import com.sw.ck.common.jdbc.DatabaseVendor;
import com.sw.ck.common.jdbc.JdbcVendorSupport;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.io.StringReader;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 将 Java String 作为 JSON 值写入数据库。
 * <p>
 * PostgreSQL/Kingbase/openGauss 的 jsonb 列不接受 JDBC 默认的 VARCHAR 参数，使用
 * {@link Types#OTHER} 让驱动按 json/jsonb 值绑定；H2 测试库同样接受该绑定。
 * MySQL/MariaDB/SQL Server 用 {@code setString}，Oracle/达梦的 CLOB 用字符流绑定。
 * 读取按列类型选择 {@code getString} 或 CLOB 字符流，业务层不感知方言。
 * </p>
 */
@MappedTypes(String.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class JsonStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter,
                                    JdbcType jdbcType) throws SQLException {
        DatabaseVendor vendor = JdbcVendorSupport.vendor(ps.getConnection());
        switch (vendor) {
            case POSTGRESQL, KINGBASE, OPENGAUSS, H2 -> ps.setObject(i, parameter, Types.OTHER);
            case ORACLE, DM -> ps.setCharacterStream(i, new StringReader(parameter), parameter.length());
            default -> ps.setString(i, parameter);
        }
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs, rs.findColumn(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs, columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }

    private String read(ResultSet rs, int columnIndex) throws SQLException {
        int type = rs.getMetaData().getColumnType(columnIndex);
        if (type == Types.CLOB || type == Types.NCLOB) {
            Clob clob = rs.getClob(columnIndex);
            return clob == null ? null : clob.getSubString(1, (int) clob.length());
        }
        return rs.getString(columnIndex);
    }
}
