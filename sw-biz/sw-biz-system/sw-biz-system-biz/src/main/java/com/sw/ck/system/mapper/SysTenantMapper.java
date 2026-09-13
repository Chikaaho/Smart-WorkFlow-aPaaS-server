package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户 Mapper（I5）。sys_tenant 为全局表（无 tenant_id 隔离语义），读取一律显式
 * 挂起租户过滤（表自身不在 ignore-tables 白名单时查询会被追加 tenant_id 条件）。
 */
@Mapper
public interface SysTenantMapper extends BaseMapperX<SysTenant> {

    /** 按主键全局读取（挂起租户过滤）。 */
    @Select("SELECT * FROM sys_tenant WHERE id = #{id} AND deleted = 0")
    SysTenant selectGlobalById(@Param("id") Long id);
}
