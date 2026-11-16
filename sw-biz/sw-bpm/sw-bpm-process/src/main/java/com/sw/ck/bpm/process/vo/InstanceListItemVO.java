package com.sw.ck.bpm.process.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程实例列表项响应 DTO。
 * <p>
 * 从 {@link com.sw.ck.bpm.process.entity.BpmInstance} 裁剪，
 * 仅保留列表展示需要的字段，不返回 tenantId/deleted/version 等内部列。
 * processName 由 Controller 通过 {@code BpmProcessDefService.findByProcessKey()} 富化。
 * </p>
 */
@Data
public class InstanceListItemVO {

    /** BpmInstance 主键 ID */
    private Long id;

    /** Flowable 流程实例 ID */
    private String processInstanceId;

    /** BPMN 流程定义 key */
    private String processDefKey;

    /** 流程名称（经 BpmProcessDefService 富化，非实体直接字段） */
    private String processName;

    /** 业务键（= 表单 recordId） */
    private String businessKey;

    /** 表单业务标识 */
    private String formKey;

    /** 发起人用户 ID */
    private Long initiatorId;
    /** 发起人展示名（real_name/username，供页面可读回显；可能为 null） */
    private String initiatorName;

    /** 实例状态：RUNNING / APPROVED / REJECTED */
    private String status;

    /** 创建时间（发起时间） */
    private LocalDateTime createTime;
}
