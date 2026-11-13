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
    /** 结构化入参 JSON（G1-4：开始节点参数名 → 值；旧契约请求为 null） */
    private String inputsJson;
    /** 结构化出参 JSON（G1-4：END 输出声明解析结果；未声明/失败为 null） */
    private String outputsJson;
    /** 父执行 id（G3 子图节点：父执行 → 子执行关联；顶层执行为 null） */
    private Long parentExecutionId;
    /** 人工节点暂停状态 JSON（G3：等待期间持久化恢复点） */
    private String pauseStateJson;
    private Long resultExecutionId;
    private String errorCategory;
    private String errorMessage;
    private Long latencyMs;
    private java.time.LocalDateTime startedAt;
    private java.time.LocalDateTime finishedAt;
    private java.time.LocalDateTime cancelRequestedAt;
}
