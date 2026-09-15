package com.sw.ck.bpm.process.mapper;

import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * I4 流程运营监控查询 Mapper（§3.3）。
 * <p>
 * 七类条件：流程定义、实例 ID、发起人、状态、节点/办理人（前置解析为实例 ID 集合）、
 * 时间范围；数据范围条件与 {@code BpmInstanceMapper} 同口径（SELF/部门三档/恒假）。
 * </p>
 */
@Mapper
public interface BpmMonitorMapper extends BaseMapperX<BpmInstance> {

    @Select("""
            <script>
            SELECT COUNT(*) FROM sw_bpm_instance
            <where>
              deleted = 0
              <if test="processDefKey != null and processDefKey != ''">AND process_def_key = #{processDefKey}</if>
              <if test="processInstanceId != null and processInstanceId != ''">AND process_instance_id = #{processInstanceId}</if>
              <if test="initiatorId != null">AND initiator_id = #{initiatorId}</if>
              <if test="status != null and status != ''">AND status = #{status}</if>
              <if test="timeFrom != null">AND create_time &gt;= #{timeFrom}</if>
              <if test="timeTo != null">AND create_time &lt;= #{timeTo}</if>
              <if test="instanceIds != null">
                <choose>
                  <when test="instanceIds.size() > 0">
                    AND process_instance_id IN
                    <foreach collection="instanceIds" item="iid" open="(" separator="," close=")">#{iid}</foreach>
                  </when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
              </if>
              <if test="scope.userId != null">AND initiator_id = #{scope.userId}</if>
              <if test="scope.deptIds != null and scope.deptIds.size() > 0">
                AND initiator_id IN (SELECT id FROM sys_user WHERE dept_id IN
                <foreach collection="scope.deptIds" item="did" open="(" separator="," close=")">#{did}</foreach>)
              </if>
              <if test="scope.deptIds != null and scope.deptIds.isEmpty()">AND 1 = 0</if>
              <if test="scope.alwaysFalse">AND 1 = 0</if>
            </where>
            </script>
            """)
    long monitorCount(@Param("processDefKey") String processDefKey,
                      @Param("processInstanceId") String processInstanceId,
                      @Param("initiatorId") Long initiatorId,
                      @Param("status") String status,
                      @Param("timeFrom") LocalDateTime timeFrom,
                      @Param("timeTo") LocalDateTime timeTo,
                      @Param("instanceIds") List<String> instanceIds,
                      @Param("scope") DataScopeFilter scope);

    @Select("""
            <script>
            SELECT * FROM sw_bpm_instance
            <where>
              deleted = 0
              <if test="processDefKey != null and processDefKey != ''">AND process_def_key = #{processDefKey}</if>
              <if test="processInstanceId != null and processInstanceId != ''">AND process_instance_id = #{processInstanceId}</if>
              <if test="initiatorId != null">AND initiator_id = #{initiatorId}</if>
              <if test="status != null and status != ''">AND status = #{status}</if>
              <if test="timeFrom != null">AND create_time &gt;= #{timeFrom}</if>
              <if test="timeTo != null">AND create_time &lt;= #{timeTo}</if>
              <if test="instanceIds != null">
                <choose>
                  <when test="instanceIds.size() > 0">
                    AND process_instance_id IN
                    <foreach collection="instanceIds" item="iid" open="(" separator="," close=")">#{iid}</foreach>
                  </when>
                  <otherwise>AND 1 = 0</otherwise>
                </choose>
              </if>
              <if test="scope.userId != null">AND initiator_id = #{scope.userId}</if>
              <if test="scope.deptIds != null and scope.deptIds.size() > 0">
                AND initiator_id IN (SELECT id FROM sys_user WHERE dept_id IN
                <foreach collection="scope.deptIds" item="did" open="(" separator="," close=")">#{did}</foreach>)
              </if>
              <if test="scope.deptIds != null and scope.deptIds.isEmpty()">AND 1 = 0</if>
              <if test="scope.alwaysFalse">AND 1 = 0</if>
            </where>
            ORDER BY create_time DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<BpmInstance> monitorList(@Param("processDefKey") String processDefKey,
                                  @Param("processInstanceId") String processInstanceId,
                                  @Param("initiatorId") Long initiatorId,
                                  @Param("status") String status,
                                  @Param("timeFrom") LocalDateTime timeFrom,
                                  @Param("timeTo") LocalDateTime timeTo,
                                  @Param("instanceIds") List<String> instanceIds,
                                  @Param("scope") DataScopeFilter scope,
                                  @Param("limit") int limit,
                                  @Param("offset") long offset);
}
