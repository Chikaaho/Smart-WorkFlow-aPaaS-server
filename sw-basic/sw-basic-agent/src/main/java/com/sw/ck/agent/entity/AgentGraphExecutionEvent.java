package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_graph_execution_event")
public class AgentGraphExecutionEvent extends BaseEntity {
    private Long executionId;
    private Long sequenceNo;
    private String eventType;
    private String nodeId;
    private String payload;
}
