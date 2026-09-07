package com.sw.ck.bpm.api.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * BPM 任务 DTO —— Facade 层出参，仅包含引擎层可知字段。
 * <p>
 * 本 DTO 不包含 formKey 等业务富化字段（由 process 层通过绑定表富化后
 * 组装为 {@code TodoTaskRespDTO} 返回前端，Step 2 处理）。
 * 字段命名对齐 {@code com.sw.ck.bpm.process.dto.TodoTaskRespDTO} 风格。
 * </p>
 *
 * @since 1.0.0
 */
@Data
public class BpmTaskDTO {

    /** Flowable task ID */
    private String taskId;

    /** 任务名称 */
    private String name;

    /** BPMN 节点稳定 key，用于动作审计与退回。 */
    private String taskDefinitionKey;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** 流程定义 key */
    private String processDefinitionKey;

    /** 当前处理人 */
    private String assignee;

    /** 候选用户（普通多人审批/会签任务使用；不暴露 Flowable IdentityLink）。 */
    private List<String> candidateUserIds;

    /** 任务创建时间（Flowable Task.getCreateTime() 原生类型） */
    private Date createTime;

    /** 业务键（= 表单 recordId，反查表单提交数据用） */
    private String businessKey;

    /** 任务完成时间（仅已办/historic 任务有值，进行中任务为 null） */
    private Date endTime;
}
