package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 发布节点允许的审批动作契约（allowedActions 白名单词汇）。
 * <p>与流程侧 {@code ApprovalAction} 请求枚举同值异职：本枚举是定义/发布层契约，
 * 请求层枚举不得越界引用定义层。</p>
 */
@Getter
public enum NodeActionTypeEnum {

    APPROVE("APPROVE", "通过"),
    DISAPPROVE("DISAPPROVE", "不同意"),
    REJECT("REJECT", "驳回"),
    RETURN("RETURN", "退回"),
    TRANSFER("TRANSFER", "转办"),
    DELEGATE("DELEGATE", "委派"),
    COMMUNICATE("COMMUNICATE", "沟通"),
    ADD_SIGN("ADD_SIGN", "加签"),
    ;

    private final String code;
    private final String label;

    NodeActionTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
