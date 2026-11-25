package com.sw.ck.agent.api.port;

import java.util.Map;

/**
 * Graph 同步调用命令。
 *
 * @param graphDefId        图定义 id（运行期按租户复核，防改库绕过发布校验）
 * @param defVersion        绑定版本（固定版本语义；null 视为非法，由调用方保证）
 * @param inputs            开始节点结构化入参（参数名 → 值；必填缺失由实现侧按声明校验失败）
 * @param timeoutSeconds    调用方等待上限（秒；null 用图自身超时；超过图上限按图上限）
 * @param businessSource    业务来源（{@link AgentGraphBusinessSources}；DIRECT 调用方可不传）
 * @param processInstanceId 流程实例 id（流程来源时必填）
 * @param nodeId            流程节点 activityId（流程来源时必填）
 * @param formRecordId      表单记录 id（表单来源时必填）
 * @param idempotencyKey    幂等键（重试/恢复不重复执行；推荐稳定键 NODE/FORM 维度）
 */
public record AgentGraphInvocation(
        Long graphDefId,
        Integer defVersion,
        Map<String, String> inputs,
        Integer timeoutSeconds,
        String businessSource,
        String processInstanceId,
        String nodeId,
        Long formRecordId,
        String idempotencyKey) {
}
