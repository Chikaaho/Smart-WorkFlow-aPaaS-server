package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 消息解析状态（IotMessageLog.parseStatus 落库契约）。
 */
@Getter
public enum IotMessageParseStatusEnum {
        RECEIVED("RECEIVED", "已接收"),
        PARSED("PARSED", "解析成功"),
        DUPLICATED("DUPLICATED", "重复消息"),
        FAILED("FAILED", "解析失败"),
    ;

    private final String code;
    private final String label;

    IotMessageParseStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
