package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实例维度节点表单历史行（按任务/轮次）。
 * <p>
 * {@code data} 为动态表单值；Long 字段沿用全局 Long→String 序列化口径。
 * </p>
 */
@Data
public class InstanceNodeFormDataDTO {

    private Long id;

    private String processInstanceId;

    private String nodeKey;

    private String taskId;

    private Long roundNo;

    private String formKey;

    private Long formVersion;

    private String status;

    private Map<String, Object> data;

    private Long submittedBy;

    private LocalDateTime submitTime;
}
