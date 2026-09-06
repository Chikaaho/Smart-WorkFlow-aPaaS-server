package com.sw.ck.bpm.process.entity;

/**
 * 命令通道。
 * <p>
 * NORMAL：普通 OA 异步通道；P0：同步优先通道（独立调度容量 + 有界等待），
 * 二者共享业务校验、审计、幂等与结果契约。
 * </p>
 */
public enum CommandChannelEnum {

    NORMAL("NORMAL"),
    P0("P0");

    private final String code;

    CommandChannelEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
