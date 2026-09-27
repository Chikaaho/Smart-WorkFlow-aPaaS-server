package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已办任务响应 DTO。
 * <p>
 * 与 {@link TodoTaskRespDTO} 对称，额外包含完成时间（endTime）。
 * </p>
 */
@Data
public class ProcessedTaskRespDTO {

    /** Flowable task ID */
    private String taskId;

    /** 任务名称 */
    private String taskName;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** 流程名称（来自 BpmProcessDef.name） */
    private String processName;

    /** 表单业务标识 */
    private String formKey;

    /** 业务键（= 表单 recordId） */
    private String businessKey;

    /** 任务创建时间 */
    private LocalDateTime createTime;

    /** 任务完成时间 */
    private LocalDateTime endTime;

    /** 实例主题（V012-BUG-010） */
    private String theme;
}
