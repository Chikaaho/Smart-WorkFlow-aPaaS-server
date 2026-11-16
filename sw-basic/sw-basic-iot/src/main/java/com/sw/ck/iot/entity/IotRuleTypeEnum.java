package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 事件规则类型（IotEventRule.ruleType 落库契约）。
 */
@Getter
public enum IotRuleTypeEnum {
        ONLINE("ONLINE", "在线规则"),
        OFFLINE("OFFLINE", "离线规则"),
    ;

    private final String code;
    private final String label;

    IotRuleTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
