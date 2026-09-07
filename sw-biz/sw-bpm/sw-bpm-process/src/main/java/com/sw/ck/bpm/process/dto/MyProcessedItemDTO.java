package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的已办条目（D4：以本人真实办理行为为权威）。
 * <p>
 * source=ACTION：本人审批动作记录（权威锚点）；
 * source=HISTORY_COMPAT：无动作记录但存在可证明的本人完成证据（引擎 finished
 * 历史且 assignee=本人）的兼容展示；被取消/删除的任务不会出现在任一来源。
 * </p>
 */
@Data
public class MyProcessedItemDTO {

    public static final String SOURCE_ACTION = "ACTION";
    public static final String SOURCE_HISTORY_COMPAT = "HISTORY_COMPAT";

    private String taskId;

    private String taskName;

    private String processInstanceId;

    private String processName;

    private String formKey;

    private String businessKey;

    /** 本人办理动作（APPROVE/REJECT/RETURN；兼容来源为 null）。 */
    private String action;

    /** 办理时间（动作时间或历史结束时间）。 */
    private LocalDateTime handleTime;

    /** 实例后续状态（RUNNING/APPROVED/REJECTED/FAILED）。 */
    private String instanceStatus;

    /** 来源：ACTION / HISTORY_COMPAT。 */
    private String source;
}
