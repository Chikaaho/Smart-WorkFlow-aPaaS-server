package com.sw.ck.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.datascope.DataScope;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.sw.ck.system.service.UserPageQuery;

import java.util.List;

/**
 * 系统用户 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapperX<SysUser> {

    @Select({"<script>",
            "SELECT DISTINCT u.id FROM sys_user u WHERE u.deleted = 0 AND u.status = 0 ",
            "AND u.tenant_id = #{tenantId} AND u.id IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    @InterceptorIgnore(tenantLine = "true")
    List<Long> selectActiveUserIds(@Param("ids") List<Long> ids, @Param("tenantId") Long tenantId);

    @Select({"<script>",
            "SELECT DISTINCT ur.user_id FROM sys_user_role ur ",
            "JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0 AND u.status = 0 ",
            "JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.status = 1 ",
            "WHERE ur.deleted = 0 AND ur.tenant_id = #{tenantId} ",
            "AND u.tenant_id = #{tenantId} AND r.tenant_id = #{tenantId} AND r.code IN ",
            "<foreach collection='roleCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach>",
            "</script>"})
    @InterceptorIgnore(tenantLine = "true")
    List<Long> selectActiveUserIdsByRoleCodes(@Param("roleCodes") List<String> roleCodes,
                                               @Param("tenantId") Long tenantId);

    /**
     * 用户分页查询（数据范围纳管入口）。
     * <p>
     * {@code @DataScope}（deptAlias/userAlias 均为空 = 单表无别名）由
     * {@code DataScopeHandler} 按当前登录人数据范围拼接条件：sys_user 同时具备
     * dept_id（部门三档）与 create_by（SELF 档）两列，五档全部由 handler 处理。
     * 逻辑删除条件手动拼接（自定义 @Select 不经过 MP 实体逻辑删除模板）。
     * </p>
     */
    @DataScope
    @Select("SELECT * FROM sys_user WHERE deleted = 0")
    IPage<SysUser> selectUserPage(Page<SysUser> page);

    @DataScope(deptAlias = "u", userAlias = "u")
    @Select({"<script>",
            "SELECT DISTINCT u.* FROM sys_user u WHERE u.deleted = 0 ",
            "<if test='q != null and q.keyword != null and q.keyword != \"\"'> AND (u.username LIKE CONCAT('%', #{q.keyword}, '%') OR u.real_name LIKE CONCAT('%', #{q.keyword}, '%')) </if>",
            "<if test='q != null and q.status != null'> AND u.status = #{q.status} </if>",
            "<if test='q != null and q.deptId != null'> AND (u.dept_id = #{q.deptId} OR u.dept_id IN (WITH RECURSIVE children(id) AS (SELECT id FROM sys_dept WHERE id = #{q.deptId} AND deleted = 0 UNION ALL SELECT d.id FROM sys_dept d JOIN children c ON d.parent_id = c.id WHERE d.deleted = 0) SELECT id FROM children)) </if>",
            "<if test='q != null and q.postId != null'> AND EXISTS (SELECT 1 FROM sys_user_post up JOIN sys_post p ON p.id = up.post_id AND p.tenant_id = up.tenant_id WHERE up.user_id = u.id AND up.post_id = #{q.postId} AND up.deleted = 0 AND up.tenant_id = u.tenant_id AND p.deleted = 0 AND p.status = 1) </if>",
            "<if test='q != null and q.roleId != null'> AND EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id WHERE ur.user_id = u.id AND ur.role_id = #{q.roleId} AND ur.deleted = 0 AND ur.tenant_id = u.tenant_id AND r.deleted = 0 AND r.status = 1 AND r.code &lt;&gt; 'superadmin') </if>",
            "</script>"})
    IPage<SysUser> selectUserPageByQuery(Page<SysUser> page, @Param("q") UserPageQuery query);
}
