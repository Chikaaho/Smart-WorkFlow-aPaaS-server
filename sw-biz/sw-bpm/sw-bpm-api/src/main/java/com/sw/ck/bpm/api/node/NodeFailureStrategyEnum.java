package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 节点失败策略契约（failureStrategy）：BLOCK 中止流程 / CONTINUE 忽略并推进。
 */
@Getter
public enum NodeFailureStrategyEnum {

    BLOCK("BLOCK", "中止"),
    CONTINUE("CONTINUE", "继续推进"),
    ;

    private final String code;
    private final String label;

    NodeFailureStrategyEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
