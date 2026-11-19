package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 流程定义状态契约（engine 描述符与 process 定义表共用词汇）。
 */
@Getter
public enum ProcessDefStatusEnum {

    DRAFT("DRAFT", "草稿"),
    PUBLISHED("PUBLISHED", "已发布"),
    ;

    private final String code;
    private final String label;

    ProcessDefStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
