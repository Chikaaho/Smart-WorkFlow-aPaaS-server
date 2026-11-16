package com.sw.ck.notify.api;

import lombok.Getter;

/**
 * 通知失败处置策略（模板/绑定配置契约）。
 * <p>RETRY 进入恢复任务自动重投；MANUAL 仅人工介入，不自动重试。</p>
 */
@Getter
public enum NotifyFailurePolicyEnum {

    RETRY("RETRY", "自动重试"),
    MANUAL("MANUAL", "人工处理"),
    ;

    private final String code;
    private final String label;

    NotifyFailurePolicyEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
