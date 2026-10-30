package com.sw.ck.bpm.engine.mapper;

import com.sw.ck.bpm.engine.entity.ExternalDatasourceGrant;
import com.sw.ck.common.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 外部数据源授权主体 Mapper。
 * <p>
 * 替换授权采用复活语义：同键软删行重新启用，避免唯一索引与软删历史冲突；
 * 复活与全量读取走显式 SQL，绕开 {@code @TableLogic} 的自动 deleted=0 过滤
 * （租户隔离仍由 {@code TenantLineHandler} 对该主库语句统一注入）。
 * </p>
 */
@Mapper
public interface ExternalDatasourceGrantMapper extends BaseMapperX<ExternalDatasourceGrant> {

    /** 指定数据源的全部授权行（含软删，用于复活判断）；租户条件由拦截器注入。 */
    @Select("SELECT id, tenant_id, create_time, create_by, update_time, update_by, deleted, version, "
            + "datasource_id, subject_type, subject_key "
            + "FROM sw_bpm_ext_datasource_grant WHERE datasource_id = #{datasourceId}")
    List<ExternalDatasourceGrant> selectAllIncludingDeleted(@Param("datasourceId") Long datasourceId);

    /** 复活软删授权行（标准 updateById 带 deleted=0 条件，无法触达软删行）。 */
    @Update("UPDATE sw_bpm_ext_datasource_grant SET deleted = 0, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id}")
    int reviveById(@Param("id") Long id);
}
