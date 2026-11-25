package com.sw.ck.agent.api.port;

import java.util.Map;

/**
 * Graph 同步调用结果（失败经字段表达，不抛业务异常）。
 *
 * @param success       是否成功（成功保证 outputs 覆盖全部必填输出声明）
 * @param executionId   受理行 id（跳转执行详情用；受理失败为 null）
 * @param outputs       结束节点输出（成功时非 null；失败为 null）
 * @param errorCategory 失败分类（TIMEOUT / GRAPH_FAILED / OUTPUT_MISSING / HUMAN_PAUSE / CAPACITY / REJECTED…）
 * @param errorMessage  失败原因（展示用）
 * @param latencyMs     实际执行耗时（毫秒）
 */
public record AgentGraphInvocationResult(
        boolean success,
        Long executionId,
        Map<String, String> outputs,
        String errorCategory,
        String errorMessage,
        Long latencyMs) {

    public static AgentGraphInvocationResult failure(Long executionId, String category, String message) {
        return new AgentGraphInvocationResult(false, executionId, null, category, message, null);
    }
}
