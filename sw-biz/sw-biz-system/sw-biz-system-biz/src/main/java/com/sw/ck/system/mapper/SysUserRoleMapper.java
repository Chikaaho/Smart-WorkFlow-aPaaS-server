package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户角色关联 Mapper。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapperX<SysUserRole> {

    /**
     * 物理清除该用户已逻辑删除的关联行。
     * <p>
     * uk_sys_user_role_tenant 唯一键包含 deleted 列：重复的角色更新会先逻辑删除当前行
     * 再插入新行，历史残留的 deleted=1 行会让第二次更新撞唯一键（停用→恢复场景必现）。
     * 关联行不承载业务审计，逻辑删除后物理清除残留是该唯一键语义成立的前提。
     */
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId} AND deleted = 1 AND tenant_id = #{tenantId}")
    int hardDeleteSoftDeletedByUser(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
