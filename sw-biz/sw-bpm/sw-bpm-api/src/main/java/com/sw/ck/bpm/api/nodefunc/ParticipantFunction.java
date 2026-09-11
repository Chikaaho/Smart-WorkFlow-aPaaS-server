package com.sw.ck.bpm.api.nodefunc;

import java.util.List;

/** 审批人解析函数契约（I3 §4.9）。实现必须是有版本约束的注册内建实现。 */
public interface ParticipantFunction {

    /**
     * 解析参与人。仅允许输出有效用户标识；空输出/异常交由注册失败策略处置。
     */
    List<String> resolveParticipants(NodeFunctionContext context);
}
