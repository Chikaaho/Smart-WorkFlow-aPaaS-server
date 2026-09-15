package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 角色菜单关联 Mapper。
 */
@Mapper
public interface SysRoleMenuMapper extends BaseMapperX<SysRoleMenu> {

    /**
     * 物理删除角色的全部菜单关联（授权重配专用）。
     * <p>
     * 关联行是纯配置关系，不走逻辑删除：唯一键 (tenant_id, role_id, menu_id, deleted)
     * 只允许一行 deleted=1，若重配时仅做软删，历史已删行会让「撤销后再授权」撞唯一键。
     * </p>
     */
    @Delete("DELETE FROM sys_role_menu WHERE role_id = #{roleId}")
    int deletePhysicallyByRoleId(@Param("roleId") Long roleId);
}
