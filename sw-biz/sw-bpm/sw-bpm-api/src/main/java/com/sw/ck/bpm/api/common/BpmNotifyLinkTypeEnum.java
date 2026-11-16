package com.sw.ck.bpm.api.common;

import lombok.Getter;

/**
 * BPM 通知链接类型（NotifyLinkVO.linkType 契约）。
 */
@Getter
public enum BpmNotifyLinkTypeEnum {

    WF_PROCESS("WF_PROCESS", "流程实例"),
    WF_TASK("WF_TASK", "流程任务"),
    ;

    private final String code;
    private final String label;

    BpmNotifyLinkTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
