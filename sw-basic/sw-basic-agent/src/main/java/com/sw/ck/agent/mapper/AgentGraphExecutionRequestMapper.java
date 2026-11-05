package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.agent.entity.AgentGraphExecutionRequest;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AgentGraphExecutionRequestMapper extends BaseMapper<AgentGraphExecutionRequest> {

    @Update("""
            UPDATE sw_agent_graph_execution_request
               SET status = #{next}, update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE id = #{id} AND status = #{expected} AND deleted = 0
            """)
    int transition(@Param("id") Long id, @Param("expected") String expected,
                   @Param("next") String next);

    @Update("""
            UPDATE sw_agent_graph_execution_request
               SET status = 'CANCEL_REQUESTED', cancel_requested_at = CURRENT_TIMESTAMP,
                   update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0
            """)
    int requestRunningCancellation(@Param("id") Long id);

    @Update("""
            UPDATE sw_agent_graph_execution_request
               SET status = 'CANCELLED', cancel_requested_at = COALESCE(cancel_requested_at, CURRENT_TIMESTAMP),
                   finished_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP, version = version + 1
             WHERE id = #{id} AND status = 'QUEUED' AND deleted = 0
            """)
    int cancelQueued(@Param("id") Long id);
}
