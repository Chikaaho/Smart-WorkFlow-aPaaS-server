package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 脚本执行结果（ScriptRunResult.status 契约）。
 */
@Getter
public enum IotScriptExecStatusEnum {
        SUCCESS("SUCCESS", "执行成功"),
        FAILED("FAILED", "执行失败"),
        TIMEOUT("TIMEOUT", "执行超时"),
    ;

    private final String code;
    private final String label;

    IotScriptExecStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
