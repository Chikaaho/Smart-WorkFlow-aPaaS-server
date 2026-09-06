package com.sw.ck.bpm.process.entity;

/**
 * 命令受理状态机：PENDING → PROCESSING → COMPLETED / FAILED。
 * <p>
 * FAILED 为有界重试后的终态（可查、不永久占住队列）；
 * PENDING 且 next_retry_at 到期后可再次被领取。
 * </p>
 */
public enum CommandStatusEnum {

    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    private final String code;

    CommandStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
