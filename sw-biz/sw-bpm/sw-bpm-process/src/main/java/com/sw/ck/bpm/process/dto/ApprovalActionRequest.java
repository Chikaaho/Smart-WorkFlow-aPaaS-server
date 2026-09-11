package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 审批动作请求；旧的无 body complete/reject 调用保持可用。 */
@Data
public class ApprovalActionRequest {

    /** 目标任务 ID（命令通道 payload 使用；HTTP 路径参数优先）。 */
    private String taskId;

    private ApprovalAction action;
    private String returnTargetNodeId;
    private String opinionFormId;
    private String opinionFormVersion;
    private String comment;
    private Map<String, Object> opinionData = new LinkedHashMap<>();

    // ==================== I3 生命周期动作扩展字段 ====================

    /** 转入人 / 受托人 / 追加参与人目标（TRANSFER/DELEGATE 用；加签用 participants）。 */
    private Long targetUserId;

    /** 加签/补签参与人列表。 */
    private java.util.List<Long> participants = new java.util.ArrayList<>();

    /** 加签顺序：SERIAL / PARALLEL。 */
    private String mode;

    /** 加签结算策略：ALL_PASS / ANY_PASS / AUTO_FINISH。 */
    private String policy;

    /** 哪个补签参与人的记录（补签表态入口）。 */
    private Long recordId;

    /** 代理规则 ID（撤销/激活）。 */
    private Long ruleId;

    /** 代理范围：GLOBAL / PROCESS / NODE / BUSINESS。 */
    private String scopeType;

    /** 代理流程定义 key 范围。 */
    private String processDefKey;

    /** 代理节点范围。 */
    private String nodeKey;

    /** 代理业务键范围。 */
    private String businessKey;

    /** 代理受控条件 JSON（保存期校验）。 */
    private String conditionJson;

    /** 生效期起（代理）。 */
    private String startAt;

    /** 生效期止（代理）。 */
    private String endAt;

    /** 撤回/废弃理由。 */
    private String reason;

    /** 沟通接收人列表。 */
    private java.util.List<Long> receivers = new java.util.ArrayList<>();

    /** 沟通回复消息。 */
    private String message;

    /** 沟通记录 ID（回复入口）。 */
    private Long communicationId;

    /** 时限自动动作配置（管理入口）。 */
    private java.util.Map<String, Object> deadline = new LinkedHashMap<>();
}
