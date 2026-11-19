package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 动态分支空集合策略契约（emptyStrategy）：BLOCK 中止 / PROCEED 按零分支推进。
 */
@Getter
public enum NodeEmptyStrategyEnum {

    BLOCK("BLOCK", "中止"),
    PROCEED("PROCEED", "零分支推进"),
    ;

    private final String code;
    private final String label;

    NodeEmptyStrategyEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
