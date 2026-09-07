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
}
