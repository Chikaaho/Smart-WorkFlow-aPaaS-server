package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审批意见暂存视图：任务、轮次、办理人与意见表单快照。
 * <p>
 * {@code opinionData} 为动态意见表单数据，保持 Map；未填写时为空 Map。
 * </p>
 */
@Data
public class OpinionDraftDTO {

    private String taskId;

    private String processInstanceId;

    private String nodeKey;

    private Integer roundNo;

    private String assigneeId;

    private String opinionFormId;

    private String opinionFormVersion;

    private String comment;

    private Map<String, Object> opinionData;

    private LocalDateTime updateTime;
}
