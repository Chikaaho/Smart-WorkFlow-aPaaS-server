package com.sw.ck.bpm.api.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * BPMN 流程活动节点 DTO（经 Facade 返回，不泄漏 Flowable 类型）。
 * <p>
 * 用于流程监控页面的流转记录展示（时间线 + 流程图高亮）。
 * activityId 与 BPMN XML 中的 bpmnElement id 对齐，前端可直接用于 bpmn-js highlight()。
 * </p>
 */
@Data
public class BpmActivityVO {

    /** BPMN 元素 ID（如 "Activity_0kx10is"，与 BPMN XML bpmnElement 对齐） */
    private String activityId;

    /** 节点名称（如 "经理审批"、"开始"） */
    private String activityName;

    /** 节点类型：userTask / startEvent / endEvent / exclusiveGateway / parallelGateway */
    private String activityType;

    /** 开始时间（可能为 null，对应未开始节点） */
    private LocalDateTime startTime;

    /** 结束时间（可能为 null，对应进行中节点） */
    private LocalDateTime endTime;

    /** 处理人（仅 userTask 类型有值，其他为 null） */
    private String assignee;

    /** 处理人展示名（real_name/username，供页面可读回显；可能为 null） */
    private String assigneeName;

    /** 关联 Flowable task ID（仅 userTask 类型有值，用于跳转任务详情） */
    private String taskId;
}
