package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * IoT 资源发布状态通用词表（设备 manageStatus / 事件规则 / 脚本共用）。
 */
@Getter
public enum IotPublishStatusEnum {
        DRAFT("DRAFT", "草稿"),
        PUBLISHED("PUBLISHED", "已发布"),
        DISABLED("DISABLED", "已停用"),
    ;

    private final String code;
    private final String label;

    IotPublishStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
