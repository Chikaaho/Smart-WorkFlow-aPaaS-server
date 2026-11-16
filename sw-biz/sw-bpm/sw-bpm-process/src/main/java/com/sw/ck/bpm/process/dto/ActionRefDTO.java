package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动作意图回查视图（触发 → 意图 → 目标实例/记录）。
 */
@Data
public class ActionRefDTO {

    private Long id;

    private Long execId;

    private String processInstanceId;

    private String triggerId;

    private String actionId;

    private String actionType;

    private String itemKey;

    private String itemSummary;

    private String commandKey;

    private Long commandId;

    private String targetDefKey;

    private String targetFormKey;

    private String targetRecordId;

    private String targetInstanceId;

    private String status;

    private String errorText;

    private LocalDateTime createTime;
}
