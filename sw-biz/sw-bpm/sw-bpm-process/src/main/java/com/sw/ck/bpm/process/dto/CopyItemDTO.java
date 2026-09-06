package com.sw.ck.bpm.process.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 抄送我的列表项（只读；不含审批操作权）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopyItemDTO {

    private Long id;

    private String processInstanceId;

    private String nodeKey;

    private String taskId;

    /** 抄送接收人（= 当前用户） */
    private String recipientId;

    private String deliveryStatus;

    private LocalDateTime createTime;

    /** 冗余上下文（列表展示用，来自实例记录） */
    private String formKey;

    private String processDefKey;

    private String businessKey;

    private Long initiatorId;

    private String instanceStatus;
}
