package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.agent.entity.AgentGraphHumanTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AgentGraphHumanTaskMapper extends BaseMapper<AgentGraphHumanTask> {

    /** 认领一次提交：仅 PENDING → SUBMITTED 成功（重复提交返回 0）。 */
    @Update("""
            UPDATE sw_agent_graph_human_task
               SET status = 'SUBMITTED', result_json = #{resultJson}, submitted_by = #{submittedBy},
                   submitted_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE id = #{id} AND status = 'PENDING' AND deleted = 0
            """)
    int claimSubmitted(@Param("id") Long id, @Param("resultJson") String resultJson,
                       @Param("submittedBy") Long submittedBy);

    /** 同一次人工等待的其余待处理行一并置 SUBMITTED（多人分配时只处理一次）。 */
    @Update("""
            UPDATE sw_agent_graph_human_task
               SET status = 'SUBMITTED', finished_at = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE execution_id = #{executionId} AND node_id = #{nodeId}
               AND status = 'PENDING' AND deleted = 0
            """)
    int completeSiblings(@Param("executionId") Long executionId, @Param("nodeId") String nodeId);

    /** 超时认领：PENDING → TIMEOUT（带超时决策结果，供恢复执行路由）。 */
    @Update("""
            UPDATE sw_agent_graph_human_task
               SET status = 'TIMEOUT', result_json = #{resultJson}, finished_at = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE id = #{id} AND status = 'PENDING' AND deleted = 0
            """)
    int claimTimeout(@Param("id") Long id, @Param("resultJson") String resultJson);

    /** 超时：同一次人工等待的其余待处理行一并置 TIMEOUT。 */
    @Update("""
            UPDATE sw_agent_graph_human_task
               SET status = 'TIMEOUT', finished_at = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE execution_id = #{executionId} AND node_id = #{nodeId}
               AND status = 'PENDING' AND deleted = 0
            """)
    int timeoutSiblings(@Param("executionId") Long executionId, @Param("nodeId") String nodeId);

    /** 等待中的执行被取消：其待处理任务置 CANCELLED。 */
    @Update("""
            UPDATE sw_agent_graph_human_task
               SET status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE execution_id = #{executionId} AND status = 'PENDING' AND deleted = 0
            """)
    int cancelPending(@Param("executionId") Long executionId);
}
