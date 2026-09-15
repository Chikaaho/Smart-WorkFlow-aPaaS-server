package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程实例记录实体。
 * <p>
 * 记录我方发起的每个 Flowable 流程实例，与 {@code ACT_HI_PROCINST}
 * 通过 {@code processInstanceId} 映射，供"我发起的"/监控查询使用。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_instance")
public class BpmInstance extends BaseEntity {

    /**
     * Flowable 流程实例 ID（对应 ACT_HI_PROCINST.ID_）。
     */
    @TableField("process_instance_id")
    private String processInstanceId;

    /**
     * BPMN 流程定义 key。
     */
    @TableField("process_def_key")
    private String processDefKey;

    /**
     * 业务键（= 表单动态宽表 recordId，反查表单数据用）。
     */
    @TableField("business_key")
    private String businessKey;

    /**
     * 表单业务标识。
     */
    @TableField("form_key")
    private String formKey;

    /**
     * 发起人用户 ID（指向 sys_user.id）。
     */
    @TableField("initiator_id")
    private Long initiatorId;

    /**
     * 实例状态：RUNNING / APPROVED / REJECTED / FAILED / WITHDRAWN / DISCARDED。
     * <p>
     * 落库 VARCHAR，接收入 {@link InstanceStatusEnum#getCode()}。
     * </p>
     */
    @TableField("status")
    private String status;

    /**
     * 发起时绑定的发布图版本号（I3 §4.3；发起时刻 def.published_version 快照）。
     */
    @TableField("def_version")
    private Integer defVersion;
}
