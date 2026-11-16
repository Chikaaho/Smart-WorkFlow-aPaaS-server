package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

import lombok.Data;

/**
 * 统一详情权限关系：当前用户与 formKey+businessKey 对应实例的关系。
 * <p>
 * {@code formHasProcess} 与实例存在性解耦，始终序列化；关系为 NONE 时
 * 其余为 null，按原 Map 契约省略，不向无关系者泄露实例信息。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstanceRelationDTO {

    /** 关系值：无关系；响应仅含 formHasProcess，其余字段按契约省略。 */
    public static final String RELATION_NONE = "NONE";

    /** 关系值：当前用户在该实例上有待办任务。 */
    public static final String RELATION_APPROVER = "APPROVER";

    /** 关系值：当前用户为发起人或历史任务参与者。 */
    public static final String RELATION_PARTICIPANT = "PARTICIPANT";

    private boolean formHasProcess;

    private String relation;

    private String pendingTaskId;

    private MyInstanceRelationInstanceDTO instance;

    private List<ApprovalHistoryItemDTO> approvalHistory;

    private Object formData;
}
