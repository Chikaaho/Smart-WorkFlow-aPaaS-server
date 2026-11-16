package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 动态分支行状态（DynamicBranchSnapshot.status 落库契约）。
 * <p>CANCELED 为终态：行被撤销后不参与分支统计与快照计数。</p>
 */
@Getter
public enum DynamicBranchRowStatusEnum {

    CANCELED("CANCELED", "已撤销"),
    ;

    private final String code;
    private final String label;

    DynamicBranchRowStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
