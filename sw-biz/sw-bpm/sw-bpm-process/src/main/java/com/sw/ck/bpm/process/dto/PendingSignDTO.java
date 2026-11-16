package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 待处理加签/补签项。
 * <p>
 * taskName/processDefinitionKey 仅在能解析到真实任务时回显，其余字段始终出现；
 * {@code serialBlocked} 表示串行模式下前序签未完成。
 * </p>
 */
@Data
public class PendingSignDTO {

    private Long recordId;

    private String taskId;

    private String processInstanceId;

    private String nodeKey;

    private String signType;

    private String modeType;

    private Integer seqNo;

    private String signPosition;

    private String comment;

    private boolean serialBlocked;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String taskName;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String processDefinitionKey;
}
