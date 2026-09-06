package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务发起草稿（{@code sw_bpm_draft}）。
 * <p>
 * 与表单设计草稿（sw_form_def DRAFT）、流程定义草稿（sw_bpm_process_def DRAFT）
 * 严格区分：本表是个人业务填报数据，归属创建人（create_by），
 * 保存不触发实例或审批。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_draft")
public class BpmDraft extends BaseEntity {

    /** 草稿标题。 */
    @TableField("title")
    private String title;

    /** 表单业务标识（必须是已发布表单）。 */
    @TableField("form_key")
    private String formKey;

    /** 保存时快照的表单发布版本（D3：发布新版本不静默改变既有草稿绑定）。 */
    @TableField("form_version")
    private Long formVersion;

    /** 选择的流程定义 key（可空 = 未选流程；提交前必须为合法已发布绑定）。 */
    @TableField("process_def_key")
    private String processDefKey;

    /** 表单业务数据（JSON）。 */
    @TableField("payload")
    private String payload;

    /** 状态（{@link DraftStatusEnum}）。 */
    @TableField("status")
    private String status;

    /** 最近一次提交的受理命令 ID。 */
    @TableField("command_id")
    private Long commandId;

    /** 提交序号（每次提交递增，参与幂等键）。 */
    @TableField("submit_seq")
    private Integer submitSeq;

    /** 提交成功后的表单记录 ID（回查表单数据）。 */
    @TableField("result_record_id")
    private String resultRecordId;

    /** 最近失败原因。 */
    @TableField("last_error")
    private String lastError;
}
