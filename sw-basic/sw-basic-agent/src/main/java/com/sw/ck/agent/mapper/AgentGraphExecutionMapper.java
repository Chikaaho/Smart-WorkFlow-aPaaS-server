package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.agent.entity.AgentGraphExecution;
import com.sw.ck.common.datascope.DataScopeFilter;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Agent 图执行记录 Mapper（M07 Step12）。
 * <p>
 * 常规数据访问走 BaseMapper + 租户拦截器（同 V21-V25 表同款惯例）；执行历史分页
 * 为本模块首个 {@code @Select} 直声明方法（数据范围纳管入口，见 {@link #selectExecutionPage}）。
 * </p>
 */
public interface AgentGraphExecutionMapper extends BaseMapper<AgentGraphExecution> {

    /**
     * 执行历史分页查询（数据范围纳管入口）。
     * <p>
     * sw_agent_graph_execution 无 dept_id 列，归属用户列为 create_by（VARCHAR(64)，
     * 存的是 userId 的字符串形态），无法用 {@code @DataScope} 标注（handler 部门档拼接
     * dept_id、SELF 档拼接 bigint 字面量，均与该列不兼容），故在本方法内以等效条件实现：
     * SELF → create_by = userId 的字符串形态（Java 侧绑定）；部门三档 → create_by IN
     * (SELECT CONCAT('', id) FROM sys_user WHERE dept_id IN (...))；空集恒假。
     * scope 由 Service 经 {@link DataScopeFilter#resolve} 从既有 SPI 解析后传入。
     * 逻辑删除条件手动拼接。
     * </p>
     */
    @Select("""
            <script>
            SELECT * FROM sw_agent_graph_execution
            <where>
              deleted = 0
              <if test="graphDefId != null">AND graph_def_id = #{graphDefId}</if>
              <if test="scope.userIdText != null">AND create_by = #{scope.userIdText}</if>
              <if test="scope.deptIds != null and scope.deptIds.size() > 0">
                AND create_by IN (SELECT CONCAT('', id) FROM sys_user WHERE dept_id IN
                <foreach collection="scope.deptIds" item="did" open="(" separator="," close=")">#{did}</foreach>)
              </if>
              <if test="scope.deptIds != null and scope.deptIds.isEmpty()">AND 1 = 0</if>
              <if test="scope.alwaysFalse">AND 1 = 0</if>
            </where>
            ORDER BY create_time DESC
            </script>
            """)
    IPage<AgentGraphExecution> selectExecutionPage(Page<AgentGraphExecution> page,
                                                   @Param("graphDefId") Long graphDefId,
                                                   @Param("scope") DataScopeFilter scope);
}
