package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 实例干预前后状态哨兵（BpmInstanceIntervention.beforeState/afterState 契约）。
 * <p>SUSPENDED 表示干预挂起；其余取值为 {@link InstanceStatusEnum} 同值码。</p>
 */
@Getter
public enum BpmInterventionStateEnum {

    SUSPENDED("SUSPENDED", "干预挂起"),
    ;

    private final String code;
    private final String label;

    BpmInterventionStateEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
