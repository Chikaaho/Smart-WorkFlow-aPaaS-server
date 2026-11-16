package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 资源准入策略状态（BpmResourcePolicy.status 落库契约）。
 */
@Getter
public enum BpmResourcePolicyStatusEnum {

    ACTIVE("ACTIVE", "生效中"),
    ;

    private final String code;
    private final String label;

    BpmResourcePolicyStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
