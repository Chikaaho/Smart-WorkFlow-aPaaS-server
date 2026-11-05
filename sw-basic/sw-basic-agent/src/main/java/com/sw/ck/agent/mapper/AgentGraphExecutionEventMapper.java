package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.agent.entity.AgentGraphExecutionEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface AgentGraphExecutionEventMapper extends BaseMapper<AgentGraphExecutionEvent> {

    @Insert("""
            INSERT INTO sw_agent_graph_execution_event
                (id, execution_id, sequence_no, event_type, node_id, payload,
                 create_time, create_by, update_time, update_by, deleted, tenant_id, version)
            SELECT #{id}, #{executionId}, COALESCE(MAX(sequence_no), 0) + 1,
                   #{eventType}, #{nodeId}, #{payload}, CURRENT_TIMESTAMP, #{createBy},
                   CURRENT_TIMESTAMP, #{createBy}, 0, #{tenantId}, 0
              FROM sw_agent_graph_execution_event
             WHERE tenant_id = #{tenantId} AND execution_id = #{executionId} AND deleted = 0
            """)
    int insertNext(@Param("id") Long id,
                   @Param("tenantId") Long tenantId,
                   @Param("createBy") String createBy,
                   @Param("executionId") Long executionId,
                   @Param("eventType") String eventType,
                   @Param("nodeId") String nodeId,
                   @Param("payload") String payload);
}
