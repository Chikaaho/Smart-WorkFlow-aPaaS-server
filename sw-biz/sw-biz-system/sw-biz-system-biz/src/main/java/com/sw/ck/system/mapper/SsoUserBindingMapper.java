package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SsoUserBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * SSO 用户绑定 Mapper（I5）。
 * <p>
 * 登录定位与绑定校验发生在认证前/跨租户场景，专用全局查询显式挂起租户过滤
 * 并自带 tenant_id 条件；业务读写走租户拦截器。
 * </p>
 */
@Mapper
public interface SsoUserBindingMapper extends BaseMapperX<SsoUserBinding> {

    /** 全局精确定位：按 (provider, tenant, external_id) 查有效绑定（认证前路径）。 */
    @Select("SELECT * FROM sys_sso_user_binding WHERE provider = #{provider} "
            + "AND tenant_id = #{tenantId} AND external_id = #{externalId} "
            + "AND bind_status = 'ACTIVE' AND deleted = 0 LIMIT 1")
    SsoUserBinding selectActiveByExternal(@Param("provider") String provider,
                                          @Param("tenantId") Long tenantId,
                                          @Param("externalId") String externalId);

    /** 全局精确查询：本地账号在指定租户内对指定 Provider 的有效绑定。 */
    @Select("SELECT * FROM sys_sso_user_binding WHERE provider = #{provider} "
            + "AND tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND bind_status = 'ACTIVE' AND deleted = 0 LIMIT 1")
    SsoUserBinding selectActiveByUser(@Param("provider") String provider,
                                      @Param("tenantId") Long tenantId,
                                      @Param("userId") Long userId);
}
