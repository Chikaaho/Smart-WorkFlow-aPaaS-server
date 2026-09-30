package com.sw.ck.bpm.process.entity;

/**
 * 命令受理状态机：PENDING → PROCESSING → COMPLETED / FAILED；PENDING → EXPIRED。
 * <p>
 * FAILED 为有界重试后的终态（可查、不永久占住队列）；
 * PENDING 且 next_retry_at 到期后可再次被领取；
 * EXPIRED 为「准入截止到期且效果未发生」的终态（P62 分级执行：只对未执行且无效果的
 * 待执行命令生效；执行中超期仅记 overdue_at，不改判失败或过期）。
 * </p>
 */
public enum CommandStatusEnum {

    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),

    /** 准入截止到期且效果未发生（安全过期；执行中不得据此判过期）。 */
    EXPIRED("EXPIRED");

    private final String code;

    CommandStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
