package com.sw.ck.bpm.process.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实例维度触发器执行记录。
 */
@Data
public class TriggerExecVO {

    private Long id;

    private String processInstanceId;

    private String triggerId;

    private String eventType;

    private String nodeKey;

    private Long roundNo;

    private String execKey;

    private String status;

    private String resultType;

    private String resultValue;

    private String matchedBranchId;

    private String disposition;

    private String errorText;

    private String snapshot;

    private Long durationMs;

    private LocalDateTime createTime;
}
