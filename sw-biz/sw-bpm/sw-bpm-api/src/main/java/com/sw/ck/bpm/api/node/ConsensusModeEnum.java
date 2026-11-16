package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 会签/投票完成模式契约（consensus.mode 与动态并行收集 mode 共用词汇）。
 */
@Getter
public enum ConsensusModeEnum {

    ALL("ALL", "全票通过"),
    ANY("ANY", "任一通过"),
    COUNT("COUNT", "按票数"),
    RATIO("RATIO", "按比例"),
    VETO("VETO", "一票否决"),
    ;

    private final String code;
    private final String label;

    ConsensusModeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
