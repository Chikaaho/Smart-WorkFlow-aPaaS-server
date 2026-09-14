package com.sw.ck.notify.dto;

import lombok.Data;

/** 通知规则 DTO（I6）。 */
@Data
public class NotifyRuleDTO {
    private Long id;
    private String ruleCode;
    private String name;
    private String eventType;
    private String channelPriority;
    private String recipientRule;
    private Boolean requiredFlag;
    private String failurePolicy;
    private Boolean enabled;
    private String remark;
}
