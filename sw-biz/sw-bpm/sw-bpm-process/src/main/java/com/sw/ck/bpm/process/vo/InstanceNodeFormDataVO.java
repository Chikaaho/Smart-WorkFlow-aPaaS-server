package com.sw.ck.bpm.process.vo;

import lombok.Data;

import java.time.LocalDateTime;
import com.sw.ck.common.value.DynamicData;

/**
 * 实例维度节点表单历史行（按任务/轮次）。
 * <p>
 * {@code data} 为动态表单值；Long 字段沿用全局 Long→String 序列化口径。
 * </p>
 */
@Data
public class InstanceNodeFormDataVO {

    private Long id;

    private String processInstanceId;

    private String nodeKey;

    private String taskId;

    private Long roundNo;

    private String formKey;

    private Long formVersion;

    private String status;

    private DynamicData data;

    private Long submittedBy;

    private LocalDateTime submitTime;
}
