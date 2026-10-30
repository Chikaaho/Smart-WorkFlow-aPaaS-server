package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.UrgeRespDTO;

/** 催办服务（v0.0.2 OA）。 */
public interface BpmUrgeService {

    /**
     * 发起人对本人仍在运行且有活动审批任务的实例催办当前实际待办人。
     * <p>
     * 冷却：同实例成功受理后 10 分钟内再次请求返回冷却信息，不重复发送；
     * 实例结束或无活动任务时拒绝并留痕；审批任务变化与实例结束由服务端核对。
     * 催办不改变审批结论。
     * </p>
     */
    UrgeRespDTO urge(Long instanceRecordId);

    /**
     * 同 {@link #urge(Long)}，附带发起人可选催办留言（最长 200 字，空白视为无留言）。
     * <p>留言随站内催办通知一并送达待办人，并进入催办留痕明细；催办不改变审批结论。</p>
     */
    default UrgeRespDTO urge(Long instanceRecordId, String message) {
        return urge(instanceRecordId);
    }

    /**
     * 同 {@link #urge(Long, String)}，可限定接收人（稳定用户 ID）。
     * <p>接收人必须是服务端核定的当前实例合法待办人的子集；空表示全部待办人（旧语义）。
     * 越权/越界接收人明确拒绝，不向非本实例参与人发送。</p>
     */
    default UrgeRespDTO urge(Long instanceRecordId, String message, java.util.List<Long> recipientIds) {
        return urge(instanceRecordId, message);
    }
}
