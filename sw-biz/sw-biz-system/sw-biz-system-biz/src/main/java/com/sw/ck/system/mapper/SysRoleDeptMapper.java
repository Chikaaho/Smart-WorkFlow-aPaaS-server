package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysRoleDept;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 角色部门关联 Mapper。
 */
@Mapper
public interface SysRoleDeptMapper extends BaseMapperX<SysRoleDept> {

    /**
     * 物理删除角色部门关联：uk_sys_role_dept 不含 deleted 列，
     * 「先删后插」必须物理删，逻辑删残留会阻断同 (role_id, dept_id) 重插。
     */
    @Delete("DELETE FROM sys_role_dept WHERE role_id = #{roleId}")
    void hardDeleteByRole(@Param("roleId") Long roleId);
}
