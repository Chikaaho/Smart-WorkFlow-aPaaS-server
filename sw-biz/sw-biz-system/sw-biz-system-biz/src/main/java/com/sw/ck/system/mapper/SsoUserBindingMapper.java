package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SsoUserBinding;
import org.apache.ibatis.annotations.Delete;
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

    /**
     * 物理删除解绑占位行（sso-admin-config A2-b）：V84 唯一索引含 deleted 列，逻辑
     * 删除的 UNBOUND 行会占用 (provider,tenant,external) 与 (provider,tenant,user) 两个
     * 唯一键，且 deleted 仅 0/1 两值——解绑/重绑第二个循环必撞键。绑定行的历史在
     * sys_sso_audit_record（BIND/UNBIND 审计），占位行本身可物理删除。
     *
     * @return 删除的占位行数
     */
    @Delete("DELETE FROM sys_sso_user_binding WHERE provider = #{provider} "
            + "AND tenant_id = #{tenantId} AND deleted = 0 AND bind_status = 'UNBOUND' "
            + "AND (external_digest = #{externalDigest} OR user_id = #{userId})")
    int deleteDormantUnboundRows(@Param("provider") String provider,
                                 @Param("tenantId") Long tenantId,
                                 @Param("externalDigest") String externalDigest,
                                 @Param("userId") Long userId);
}
