package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审批历史条目 DTO。
 * <p>
 * 代表一个已完成的审批节点，记录谁在什么时候做了审批。
 * </p>
 */
@Data
public class ApprovalHistoryItemDTO {

    /** Flowable task ID */
    private String taskId;

    /** 任务名称（如"审批"） */
    private String taskName;

    /** 稳定节点标识；退回只能选择已经过的人工节点。 */
    private String nodeKey;

    /** 处理人用户 ID */
    private String assignee;
    /** 审批人展示名（可读身份回显） */
    private String assigneeName;

    /** 任务创建时间 */
    private LocalDateTime createTime;

    /** 任务完成时间 */
    private LocalDateTime endTime;

    /** APPROVE / RETURN / REJECT；旧 Flowable 历史没有该字段时为 null。 */
    private String action;

    /** APPROVED / REJECTED；退回及旧历史数据为 null。 */
    private String approvalResult;

    /** 不可变审批意见快照。 */
    private Map<String, Object> opinionData;

    private String opinionFormId;
    private String opinionFormVersion;
}
