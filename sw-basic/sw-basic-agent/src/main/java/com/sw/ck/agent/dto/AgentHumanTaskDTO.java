package com.sw.ck.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 人工任务视图（G3）：待我处理列表与执行详情共用。
 * payload（说明/字段/按钮/超时）在落库时为 JSON，返回前解析为结构。
 */
@Data
public class AgentHumanTaskDTO {

    private Long id;

    private Long executionId;

    private Long graphDefId;

    private String nodeId;

    /** PENDING / SUBMITTED / TIMEOUT / CANCELLED */
    private String status;

    /** 给处理人看的说明（已按暂停时变量插值） */
    private String instructions;

    /** 要填写的字段（name/label/type/required/options） */
    private List<Map<String, Object>> fields;

    /** 决策按钮（keyword/label） */
    private List<Map<String, Object>> buttons;

    /** 超时时长（秒，0/空 = 不超时） */
    private Integer timeoutSeconds;

    private Long assigneeUserId;

    private String assigneeRoleCode;

    /** 提交结果 JSON（填写值 + 决策动作） */
    private String resultJson;

    private LocalDateTime createTime;

    private LocalDateTime submittedAt;
}
