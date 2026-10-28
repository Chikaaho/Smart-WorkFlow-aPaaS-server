package com.sw.ck.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.datascope.DataScope;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户组 Mapper。
 * <p>
 * 数据范围：用户组主表没有 dept_id 列（扁平虚拟集合），无法直接用
 * {@link DataScopeHandler} 的 dept_id 拼接，因此 {@link #selectGroupPage} 用
 * 「组内成员用户所属部门命中当前登录人数据范围」的 EXISTS 子查询表达数据范围，
 * 并把 {@code @DataScope} 标注在组成员用户表 {@code sys_user}（有 dept_id/create_by 列，
 * 由 DataScopeHandler 拼接）的子查询上——语义上等价于“只能看到其数据范围内成员构成的组”。
 * 逻辑删除条件手动拼接（自定义 @Select 不经过 MP 实体逻辑删除模板）。
 * </p>
 */
@Mapper
public interface SysUserGroupMapper extends BaseMapperX<SysUserGroup> {

    /** 用户组分页查询（数据范围纳管：超管全量；普通用户看自建组或成员命中数据范围的组）。 */
    @Select({"<script>",
            "SELECT DISTINCT g.* FROM sys_user_group g WHERE g.deleted = 0 ",
            // 自定义 @Select 不经过 MP 租户模板，显式按当前租户过滤，避免跨租户组泄露
            "AND g.tenant_id = #{__tenantId__} ",
            // 超管看全部（含空成员组）；普通用户看自己创建的组，或成员命中数据范围的组。
            // 空成员组若不可见，创建后无法在列表中找到并补充成员，属显示与数据不一致。
            "<choose>",
            "  <when test='__superAdmin__'>AND 1 = 1</when>",
            "  <otherwise>",
            "    AND (g.create_by = #{__userId__} OR EXISTS (SELECT 1 FROM sys_user um WHERE um.deleted = 0 ",
            "      AND um.id IN (SELECT gm.user_id FROM sys_user_group_member gm WHERE gm.group_id = g.id AND gm.deleted = 0)",
            "      AND um.tenant_id = g.tenant_id ",
            "      <choose>",
            "        <when test='__scopeType__ == \"SELF\"'>AND um.create_by = #{__userId__}</when>",
            "        <when test='__scopeType__ == \"DEPT\"'>AND um.dept_id = #{__deptId__}</when>",
            "        <when test='__scopeType__ == \"DEPT_AND_CHILD\"'>AND um.dept_id IN (",
            "          WITH RECURSIVE children(id) AS (",
            "            SELECT id FROM sys_dept WHERE id = #{__deptId__} AND deleted = 0",
            "            UNION ALL SELECT d.id FROM sys_dept d JOIN children c ON d.parent_id = c.id WHERE d.deleted = 0",
            "          ) SELECT id FROM children)",
            "        </when>",
            "        <when test='__scopeType__ == \"CUSTOM\"'>",
            "          <choose>",
            "            <when test='__customDeptIds__ != null and __customDeptIds__.size() > 0'>AND um.dept_id IN",
            "              <foreach collection='__customDeptIds__' item='d' open='(' separator=',' close=')'>#{d}</foreach>",
            "            </when>",
            "            <otherwise>AND 1 = 0</otherwise>",
            "          </choose>",
            "        </when>",
            "      </choose>",
            "    ))",
            "  </otherwise>",
            "</choose>",
            "<if test='q != null and q.groupCode != null and q.groupCode != \"\"'> AND g.group_code LIKE CONCAT('%', #{q.groupCode}, '%') </if>",
            "<if test='q != null and q.groupName != null and q.groupName != \"\"'> AND g.group_name LIKE CONCAT('%', #{q.groupName}, '%') </if>",
            "<if test='q != null and q.status != null'> AND g.status = #{q.status} </if>",
            "</script>"})
    IPage<SysUserGroup> selectGroupPage(Page<SysUserGroup> page,
                                        @Param("q") SysUserGroup query,
                                        @Param("__superAdmin__") boolean superAdmin,
                                        @Param("__scopeType__") String scopeType,
                                        @Param("__userId__") Long userId,
                                        @Param("__deptId__") Long deptId,
                                        @Param("__customDeptIds__") java.util.List<Long> customDeptIds,
                                        @Param("__tenantId__") Long tenantId);

    /** 组成员用户候选分页：仅启用用户 + 数据范围限定（与组列表同一套 EXISTS 语义）。 */
    @Select({"<script>",
            "SELECT DISTINCT u.id, u.username, u.real_name, u.dept_id, u.status ",
            "FROM sys_user u WHERE u.deleted = 0 AND u.status = 0 ",
            // 自定义 @Select 不经过 MP 租户模板，显式按当前租户过滤，避免跨租户候选泄露
            "AND u.tenant_id = #{__tenantId__} ",
            "AND (" +
            "  <choose>",
            "    <when test='__superAdmin__'>1 = 1</when>",
            "    <otherwise>",
            "      <choose>",
            "        <when test='__scopeType__ == \"SELF\"'>u.create_by = #{__userId__}</when>",
            "        <when test='__scopeType__ == \"DEPT\"'>u.dept_id = #{__deptId__}</when>",
            "        <when test='__scopeType__ == \"DEPT_AND_CHILD\"'>u.dept_id IN (",
            "          WITH RECURSIVE children(id) AS (",
            "            SELECT id FROM sys_dept WHERE id = #{__deptId__} AND deleted = 0",
            "            UNION ALL SELECT d.id FROM sys_dept d JOIN children c ON d.parent_id = c.id WHERE d.deleted = 0",
            "          ) SELECT id FROM children)",
            "        </when>",
            "        <when test='__scopeType__ == \"CUSTOM\"'>",
            "          <choose>",
            "            <when test='__customDeptIds__ != null and __customDeptIds__.size() > 0'>u.dept_id IN",
            "              <foreach collection='__customDeptIds__' item='d' open='(' separator=',' close=')'>#{d}</foreach>",
            "            </when>",
            "            <otherwise>1 = 0</otherwise>",
            "          </choose>",
            "        </when>",
            "        <otherwise>1 = 0</otherwise>",
            "      </choose>",
            "    </otherwise>",
            "  </choose>",
            "  ) ",
            "<if test='keyword != null and keyword != \"\"'> AND (u.username LIKE CONCAT('%', #{keyword}, '%') OR u.real_name LIKE CONCAT('%', #{keyword}, '%')) </if>",
            "</script>"})
    IPage<SysUser> selectMemberCandidates(Page<SysUser> page,
                                          @Param("keyword") String keyword,
                                          @Param("__superAdmin__") boolean superAdmin,
                                          @Param("__scopeType__") String scopeType,
                                          @Param("__userId__") Long userId,
                                          @Param("__deptId__") Long deptId,
                                          @Param("__customDeptIds__") java.util.List<Long> customDeptIds,
                                          @Param("__tenantId__") Long tenantId);
}
