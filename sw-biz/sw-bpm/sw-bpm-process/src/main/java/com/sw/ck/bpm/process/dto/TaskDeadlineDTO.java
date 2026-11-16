package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实例办理时限行：任务、节点、到期时间、已办理/超时标记。
 */
@Data
public class TaskDeadlineDTO {

    private String taskId;

    private String nodeKey;

    private LocalDateTime dueAt;

    private boolean handled;

    private boolean overdue;
}
