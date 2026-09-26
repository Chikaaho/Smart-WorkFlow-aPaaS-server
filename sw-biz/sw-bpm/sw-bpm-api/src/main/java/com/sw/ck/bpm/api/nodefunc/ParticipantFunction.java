package com.sw.ck.bpm.api.nodefunc;

import java.util.List;
import java.util.Optional;

/** 审批人解析函数契约（I3 §4.9）。实现必须是有版本约束的注册内建实现。 */
public interface ParticipantFunction {

    /**
     * 解析参与人。仅允许输出有效用户标识；空输出/异常交由注册失败策略处置。
     *
     * @return present = 解析出的参与人标识列表（解析成功但无有效参与人时为空列表，
     *         该事实交由失败策略裁决）；当前契约恒 present，解析失败抛明确异常
     */
    Optional<List<String>> resolveParticipants(NodeFunctionContext context);
}
