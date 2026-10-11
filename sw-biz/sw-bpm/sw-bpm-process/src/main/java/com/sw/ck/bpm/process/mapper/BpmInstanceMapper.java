package com.sw.ck.bpm.process.mapper;

import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 流程实例记录 Mapper。
 */
@Mapper
public interface BpmInstanceMapper extends BaseMapperX<BpmInstance> {

    /**
     * 流程实例计数（数据范围纳管入口）。
     * <p>
     * sw_bpm_instance 无 dept_id 列，归属用户列为 initiator_id，无法用
     * {@code @DataScope} 标注（handler 部门档拼接 dept_id、SELF 档拼接 create_by），
     * 故在本方法内以等效条件实现：SELF → initiator_id = userId；部门三档 →
     * initiator_id IN (SELECT id FROM sys_user WHERE dept_id IN (...))；空集恒假。
     * scope 由 Service 经 {@link DataScopeFilter#resolve} 从既有 SPI 解析后传入。
     * 逻辑删除条件手动拼接（自定义 @Select 不经过 MP 实体逻辑删除模板）。
     * </p>
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM sw_bpm_instance
            <where>
              deleted = 0
              <if test="status != null and status != ''">AND status = #{status}</if>
              <if test="processDefKey != null and processDefKey != ''">AND process_def_key = #{processDefKey}</if>
              <if test="initiatorId != null">AND initiator_id = #{initiatorId}</if>
              <if test="businessKey != null and businessKey != ''">AND business_key = #{businessKey}</if>
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
    long selectInstanceCount(@Param("status") String status,
                             @Param("processDefKey") String processDefKey,
                             @Param("initiatorId") Long initiatorId,
                             @Param("businessKey") String businessKey,
                             @Param("scope") DataScopeFilter scope);

    /**
     * 流程实例分页列表（数据范围纳管入口，条件同 {@link #selectInstanceCount}）。
     * <p>
     * 保留原实现的分页形态（COUNT 与 LIST 分离、LIST 显式 LIMIT/OFFSET，规避
     * H2 PostgreSQL 模式 COUNT + ORDER BY 的问题），不引入 MP 分页拦截器。
     * </p>
     */
    @Select("""
            <script>
            SELECT * FROM sw_bpm_instance
            <where>
              deleted = 0
              <if test="status != null and status != ''">AND status = #{status}</if>
              <if test="processDefKey != null and processDefKey != ''">AND process_def_key = #{processDefKey}</if>
              <if test="initiatorId != null">AND initiator_id = #{initiatorId}</if>
              <if test="businessKey != null and businessKey != ''">AND business_key = #{businessKey}</if>
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
    List<BpmInstance> selectInstanceList(@Param("status") String status,
                                         @Param("processDefKey") String processDefKey,
                                         @Param("initiatorId") Long initiatorId,
                                         @Param("businessKey") String businessKey,
                                         @Param("scope") DataScopeFilter scope,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    /**
     * 根链互斥点：按实例 ID 加行锁（FOR UPDATE，调用方事务内持有至提交）。
     * <p>
     * P64 根链护栏（复审05 P2-06a-C）以"同根批次 expected_count 求和→插入"判定额度，
     * 该检查-后插入序列在两个不同 batch_key 的同根并发派发下可能同时通过陈旧快照；
     * 同一根链的全部派发解析到同一 root_instance_id，对其根实例行加锁即把同根
     * 冻结串行化，使计数守卫与插入在同一根链上原子成立。行不存在（数据异常）返回
     * null，由调用方按原语义继续（不因锁目标缺失新增派发失败模式）。
     * </p>
     */
    @Select("SELECT id FROM sw_bpm_instance WHERE process_instance_id = #{processInstanceId} "
            + "AND deleted = 0 FOR UPDATE")
    Long lockByProcessInstanceIdForUpdate(@Param("processInstanceId") String processInstanceId);
}
