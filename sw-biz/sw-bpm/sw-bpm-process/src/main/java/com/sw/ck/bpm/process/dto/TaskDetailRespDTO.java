package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 任务详情响应 DTO。
 * <p>
 * 包含任务基本信息、发起人信息、流程变量等。
 * </p>
 */
@Data
public class TaskDetailRespDTO {

    /** Flowable task ID */
    private String taskId;

    /** 任务名称 */
    private String taskName;

    /** 任务状态：RUNNING=待办进行中；FINISHED=已办结（历史任务，详情只读） */
    private String taskStatus;

    /** 流程实例状态（业务实例记录：RUNNING/APPROVED/REJECTED/FAILED 等；无记录时为 null） */
    private String instanceStatus;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** 流程定义 key */
    private String processDefinitionKey;

    /** 流程名称 */
    private String processName;

    /** 表单业务标识 */
    private String formKey;

    /** 业务键（= 表单 recordId，反查表单提交数据用） */
    private String businessKey;

    /** 当前处理人 */
    private String assignee;
    /** 审批人展示名（可读身份回显） */
    private String assigneeName;

    /** 当前登录用户是否可办理该任务（服务端办理权限判定；已办历史任务恒 false） */
    private Boolean canHandle;

    /** 发起人 ID */
    private Long initiatorId;
    /** 发起人展示名（可读身份回显） */
    private String initiatorName;

    /** 任务创建时间 */
    private LocalDateTime createTime;

    /** 流程变量 */
    private Map<String, Object> processVariables;

    /** 当前人工节点配置的低代码审批意见表单；无配置时为空对象。 */
    private Map<String, Object> opinionForm;

    /** 该流程实例的审批历史（按完成时间倒序） */
    private List<ApprovalHistoryItemDTO> approvalHistory;
}
