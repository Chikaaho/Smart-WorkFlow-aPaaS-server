package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人工节点任务（G3）：一次人工等待一行或多行（按处理人用户/角色拆分）。
 * 同一 execution + node 的多个待处理行共享 payload（表单定义）；提交后全部置 SUBMITTED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_graph_human_task")
public class AgentGraphHumanTask extends BaseEntity {

    private Long executionId;

    private String nodeId;

    /** 状态：PENDING / SUBMITTED / TIMEOUT / CANCELLED */
    private String status;

    /** 指定处理人用户 id（按用户分配时非空） */
    private Long assigneeUserId;

    /** 指定处理人角色 code（按角色分配时非空） */
    private String assigneeRoleCode;

    /** 任务表单定义 JSON（说明/字段/按钮/超时配置） */
    private String payloadJson;

    /** 提交结果 JSON（填写值 + 决策动作） */
    private String resultJson;

    private Long submittedBy;

    private java.time.LocalDateTime submittedAt;

    private java.time.LocalDateTime finishedAt;
}
