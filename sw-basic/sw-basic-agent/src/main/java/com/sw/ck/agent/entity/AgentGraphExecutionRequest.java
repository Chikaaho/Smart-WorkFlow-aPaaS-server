package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import com.sw.ck.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

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
    /** 业务来源（1.2.0 E1-7：PROCESS/FORM/DIRECT；存量行为 null，读侧按 DIRECT 展示） */
    private String businessSource;
    /** 业务关联：流程实例 id（流程服务节点触发时写入） */
    private String processInstanceId;
    /** 业务关联：流程节点 activityId */
    private String nodeId;
    /** 业务关联：表单记录 id（表单服务端动作触发时写入） */
    private Long formRecordId;
    private Long resultExecutionId;
    private String errorCategory;
    private String errorMessage;
    private Long latencyMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime cancelRequestedAt;
}
