package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 异步图执行受理记录。执行意图先持久化，再由有界调度器领取。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_graph_execution_request")
public class AgentGraphExecutionRequest extends BaseEntity {
    private Long graphDefId;
    private Integer graphDefVersion;
    private String idempotencyKey;
    private String requestFingerprint;
    private String status;
    private String input;
    private Long resultExecutionId;
    private String errorCategory;
    private String errorMessage;
    private Long latencyMs;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private java.time.LocalDateTime cancelRequestedAt;
}
