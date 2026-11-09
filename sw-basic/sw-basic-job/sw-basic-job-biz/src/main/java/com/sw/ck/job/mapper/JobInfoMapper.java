package com.sw.ck.job.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.job.entity.JobInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 定时任务定义 Mapper。
 */
@Mapper
public interface JobInfoMapper extends BaseMapperX<JobInfo> {

    /**
     * 任务定义分页查询（数据范围纳管入口）。
     * <p>
     * sw_job_info 无 dept_id 列，归属用户列为 create_by，无法用 {@code @DataScope}
     * 标注（handler 部门档拼接 dept_id），故在本方法内以等效条件实现：SELF →
     * create_by = userId；部门三档 → create_by IN (SELECT id FROM sys_user WHERE
     * dept_id IN (...))；空集恒假。scope 由 Service 经 {@link DataScopeFilter#resolve}
     * 从既有 SPI 解析后传入。逻辑删除条件手动拼接。
     * </p>
     */
    @Select("""
            <script>
            SELECT * FROM sw_job_info
            <where>
              deleted = 0
              <if test="jobName != null and jobName != ''">AND job_name LIKE CONCAT(CONCAT('%', #{jobName}), '%')</if>
              <if test="jobType != null and jobType != ''">AND job_type = #{jobType}</if>
              <if test="status != null and status != ''">AND status = #{status}</if>
              <if test="scope.userId != null">AND create_by = #{scope.userId}</if>
              <if test="scope.deptIds != null and scope.deptIds.size() > 0">
                AND create_by IN (SELECT id FROM sys_user WHERE dept_id IN
                <foreach collection="scope.deptIds" item="did" open="(" separator="," close=")">#{did}</foreach>)
              </if>
              <if test="scope.deptIds != null and scope.deptIds.isEmpty()">AND 1 = 0</if>
              <if test="scope.alwaysFalse">AND 1 = 0</if>
            </where>
            ORDER BY create_time DESC
            </script>
            """)
    IPage<JobInfo> selectJobInfoPage(Page<JobInfo> page,
                                     @Param("jobName") String jobName,
                                     @Param("jobType") String jobType,
                                     @Param("status") String status,
                                     @Param("scope") DataScopeFilter scope);
}
