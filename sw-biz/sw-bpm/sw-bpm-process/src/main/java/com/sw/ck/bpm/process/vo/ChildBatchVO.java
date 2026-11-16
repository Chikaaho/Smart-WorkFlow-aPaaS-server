package com.sw.ck.bpm.process.vo;

import com.sw.ck.bpm.process.service.ChildOrchestrationService.ChildItemView;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 子流程派发批次回查视图：批次状态、计数、等待原因与批次项。
 */
@Data
public class ChildBatchVO {

    private Long id;

    private String batchKey;

    private String parentInstanceId;

    private String triggerId;

    private String actionId;

    private Long roundNo;

    private String waitPolicy;

    private Integer waitCount;

    private Integer expectedCount;

    private Integer settledCount;

    private String status;

    private String blockReason;

    private LocalDateTime settledAt;

    private Integer parentDepth;

    private List<ChildItemView> items;
}
