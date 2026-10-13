package com.sw.ck.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 部门 Mapper。
 */
@Mapper
public interface SysDeptMapper extends BaseMapperX<SysDept> {

    /**
     * BF20261013-021：部门目录（部门ID / 上级部门ID / 有效负责人ID），供流程脚本组织函数快照。
     * 负责人须为同租户启用用户；未设置或无效为 NULL（部门行仍返回）。
     */
    @Select("SELECT d.id AS dept_id, d.parent_id AS parent_id, l.id AS leader_id FROM sys_dept d "
            + "LEFT JOIN sys_user l ON l.id = d.leader_id AND l.deleted = 0 AND l.status = 0 "
            + "AND l.tenant_id = d.tenant_id "
            + "WHERE d.deleted = 0 AND d.status = 0 AND d.tenant_id = #{tenantId}")
    @InterceptorIgnore(tenantLine = "true")
    List<java.util.Map<String, Object>> selectDeptDirectory(@Param("tenantId") Long tenantId);
}
