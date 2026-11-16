package com.sw.ck.bpm.process.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 批量审批单项结果。
 * <p>
 * taskId/action/success 始终回显；errorCode/message 仅在失败项出现，
 * 与原 Map 逐项结果形状一致。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchActionResultVO {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String taskId;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String action;

    private boolean success;

    private Integer errorCode;

    private String message;
}
