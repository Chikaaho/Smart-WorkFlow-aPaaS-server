package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 动态分支无效项策略契约（invalidStrategy）：BLOCK 中止 / SKIP 剔除无效项继续。
 */
@Getter
public enum NodeInvalidStrategyEnum {

    BLOCK("BLOCK", "中止"),
    SKIP("SKIP", "剔除后继续"),
    ;

    private final String code;
    private final String label;

    NodeInvalidStrategyEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
