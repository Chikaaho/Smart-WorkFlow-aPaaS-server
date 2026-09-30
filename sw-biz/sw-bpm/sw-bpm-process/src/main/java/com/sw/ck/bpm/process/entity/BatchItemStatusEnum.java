package com.sw.ck.bpm.process.entity;

/**
 * 批量命令项状态：PENDING → SUCCEEDED / REJECTED。
 * <p>
 * REJECTED 涵盖业务拒绝（余额不足等）与同键异载荷冲突（错误码区分）；
 * 两者都是确定性业务终态，不触发命令重试。
 * </p>
 */
public enum BatchItemStatusEnum {

    PENDING("PENDING"),
    SUCCEEDED("SUCCEEDED"),
    REJECTED("REJECTED");

    private final String code;

    BatchItemStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
