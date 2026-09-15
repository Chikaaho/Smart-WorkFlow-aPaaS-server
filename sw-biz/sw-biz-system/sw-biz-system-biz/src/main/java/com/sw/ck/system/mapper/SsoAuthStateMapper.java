package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SsoAuthState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * SSO 授权 state Mapper（I5）。state 查找发生在认证前（无租户上下文），
 * 专用全局查询显式挂起租户过滤并自带 tenant_id 条件。
 */
@Mapper
public interface SsoAuthStateMapper extends BaseMapperX<SsoAuthState> {

    /** 按 state 值全局查找（认证前路径；回调校验用）。 */
    @Select("SELECT * FROM sys_sso_auth_state WHERE state_value = #{stateValue} AND deleted = 0 LIMIT 1")
    SsoAuthState selectGlobalByState(@Param("stateValue") String stateValue);
}
