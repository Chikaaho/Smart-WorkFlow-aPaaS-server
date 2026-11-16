package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一详情关系端点中的实例摘要（不泄露完整实体字段）。
 */
@Data
public class MyInstanceRelationInstanceDTO {

    private Long id;

    private String processInstanceId;

    private String processDefKey;

    private String processName;

    private String theme;

    private String status;

    private Long initiatorId;

    private String initiatorName;

    private LocalDateTime createTime;

    private List<String> currentNodes;

    private List<String> currentNodeKeys;
}
