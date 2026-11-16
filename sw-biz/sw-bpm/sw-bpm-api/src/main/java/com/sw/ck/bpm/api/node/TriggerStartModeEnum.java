package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 触发器批量启动模式契约（startMode）。
 */
@Getter
public enum TriggerStartModeEnum {

    START_SINGLE("START_SINGLE", "单实例启动"),
    START_EACH("START_EACH", "逐项启动"),
    START_GROUPED("START_GROUPED", "分组启动"),
    ;

    private final String code;
    private final String label;

    TriggerStartModeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
