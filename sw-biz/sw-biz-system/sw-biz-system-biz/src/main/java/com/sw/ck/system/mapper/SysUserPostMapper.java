package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysUserPost;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysUserPostMapper extends BaseMapperX<SysUserPost> {

    /** 与 SysUserRoleMapper.hardDeleteSoftDeletedByUser 同因：uk_sys_user_post 含 deleted 列。 */
    @Delete("DELETE FROM sys_user_post WHERE user_id = #{userId} AND deleted = 1 AND tenant_id = #{tenantId}")
    int hardDeleteSoftDeletedByUser(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
