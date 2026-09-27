package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待办任务响应 DTO。
 * <p>
 * 返回给前端的待办列表项，包含任务标识、流程实例标识、表单信息、业务键等。
 * formKey 和 businessKey 由 process 层通过绑定表/流程变量富化后组装。
 * </p>
 */
@Data
public class TodoTaskRespDTO {

    /** Flowable task ID */
    private String taskId;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** 流程名称 */
    private String processName;

    /** 表单业务标识 */
    private String formKey;

    /** 业务键（= 表单 recordId，反查表单提交数据用） */
    private String businessKey;

    /** 任务创建时间 */
    private LocalDateTime createTime;

    /** 实例主题（V012-BUG-010：发起时按主题规则生成） */
    private String theme;

    /** 申请人展示名（V012-BUG-010 待办改版） */
    private String initiatorName;

    /** 流程状态（待办固定「待审」语义；供列表列展示） */
    private String flowStatus;

    /** 流程定义键（V012-BUG-010 定位分类用） */
    private String processDefKey;
}
