package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

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

    private boolean formHasProcess;

    private String relation;

    private String pendingTaskId;

    private MyInstanceRelationInstanceDTO instance;

    private List<ApprovalHistoryItemDTO> approvalHistory;

    private Object formData;
}
