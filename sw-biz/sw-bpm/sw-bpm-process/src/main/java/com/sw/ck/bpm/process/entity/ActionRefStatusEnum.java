package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 动作引用状态（BpmActionRef.status 落库契约）。
 * <p>INTENT_SUBMITTED 同事务受理事实 → STARTING 异步创建中 → STARTED 消费事务回填目标 /
 * FAILED 终态；展示层按目标实例持久事实回查，不直接信任 STARTED。</p>
 */
@Getter
public enum ActionRefStatusEnum {

    INTENT_SUBMITTED("INTENT_SUBMITTED", "已受理"),
    STARTING("STARTING", "创建中"),
    STARTED("STARTED", "已启动"),
    FAILED("FAILED", "失败"),
    ;

    private final String code;
    private final String label;

    ActionRefStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
