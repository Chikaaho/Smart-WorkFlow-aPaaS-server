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
