package com.sw.ck.job.typehandler;

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
 *
 * <p>背景（真实 PostgreSQL 行为）：{@code sw_job_info.concurrent} 与 {@code sw_job_log} 相关
 * 布尔语义列在迁移里是 {@code SMALLINT}，而实体字段是 {@link Boolean}。PostgreSQL 不接受
 * {@code boolean → smallint} 的隐式转换（{@code ERROR: column "concurrent" is of type smallint
 * but expression is of type boolean}），H2 则会隐式转换，因此该问题只在生产语义方言上暴露。
 * 本处理器把布尔写成 0/1，读回用小整数解释，实体与对外 DTO 的类型保持不变。</p>
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
