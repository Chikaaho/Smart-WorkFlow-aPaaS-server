package com.sw.ck.agent.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code smallint} 布尔列的类型适配。
 * <p>
 * {@code sw_agent_tool_external.enabled}/{@code sw_agent_tool_internal.enabled} 在迁移中是
 * {@code SMALLINT}，实体字段是 {@link Boolean}。PostgreSQL 不接受 {@code boolean → smallint}
 * 隐式转换（H2 会隐式转换），因此工具登记在 PG 上会失败。本处理器把布尔写成 0/1，
 * 读回按小整数解释，实体与 DTO 类型保持不变。
 * </p>
 */
@MappedTypes(Boolean.class)
@MappedJdbcTypes(JdbcType.SMALLINT)
public class BooleanSmallintTypeHandler extends BaseTypeHandler<Boolean> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Boolean parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, Boolean.TRUE.equals(parameter) ? 1 : 0);
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getBoolean(columnName);
    }

    @Override
    public Boolean getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getBoolean(columnIndex);
    }

    @Override
    public Boolean getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getBoolean(columnIndex);
    }
}
