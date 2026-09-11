package com.sw.ck.bpm.process.dto;

/**
 * I3 统一审批动作契约。
 * <p>
 * 语义边界（不可混称，见方向 §4.4—§4.7）：
 * APPROVE  当前参与人通过动作，进入节点结算规则；
 * DISAPPROVE 当前参与人的不通过意见，交由当前节点的明确结算规则处理；
 * RETURN   退回本实例已通过且设计允许的目标人工节点，生成新的办理轮次；
 * REJECT   流程级驳回终态，关闭全部可办理任务；
 * TRANSFER / DELEGATE / AUTHORIZE / ADD_SIGN / SUPPLEMENT_SIGN / WITHDRAW /
 * COMMUNICATE / DISCARD 生命周期动作见各自方向条款。
 */
public enum ApprovalAction {
    APPROVE,
    RETURN,
    REJECT,
    DISAPPROVE,
    TRANSFER,
    DELEGATE,
    AUTHORIZE,
    ADD_SIGN,
    SUPPLEMENT_SIGN,
    WITHDRAW,
    COMMUNICATE,
    DISCARD
}
