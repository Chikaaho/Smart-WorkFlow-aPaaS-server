package com.sw.ck.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysDictType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 字典类型 Mapper。
 */
@Mapper
public interface SysDictTypeMapper extends BaseMapperX<SysDictType> {

    /**
     * 跨租户探测指定编码的活跃字典类型归属租户（0.2.2 F2 导入占用探测，D022-13）。
     * <p>全局唯一索引 {@code uk_sys_dict_type_code} 为 (code, deleted)，编码占用是
     * 跨租户事实，故本方法豁免租户行级拦截（方法级 {@code @InterceptorIgnore}，
     * 与 SysUserMapper 先例一致）；只读探测，不返回类型数据。</p>
     *
     * @return 持有该编码活跃行的最小 tenant_id；全局空闲返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT MIN(tenant_id) FROM sys_dict_type WHERE code = #{code} AND deleted = 0")
    Long selectActiveOwnerTenantId(@Param("code") String code);
}
