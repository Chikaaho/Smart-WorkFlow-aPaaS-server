package com.sw.ck.bpm.process.entity;

/**
 * 批量命令批次状态机：PENDING → PROCESSING → COMPLETED / PARTIALLY_FAILED。
 * <p>
 * COMPLETED = 全部项 SUCCEEDED；存在 REJECTED 项时为 PARTIALLY_FAILED。
 * 重入恢复只处理 PENDING 项，已终态项不重做。
 * </p>
 */
public enum BatchStatusEnum {

    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    PARTIALLY_FAILED("PARTIALLY_FAILED");

    private final String code;

    BatchStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
