package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 触发器动作类型（IotProcessTrigger.actionType 落库契约）。
 */
@Getter
public enum IotTriggerActionTypeEnum {
        AGENT("AGENT", "Agent 执行"),
        FLOW("FLOW", "流程启动"),
    ;

    private final String code;
    private final String label;

    IotTriggerActionTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
