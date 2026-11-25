package com.sw.ck.agent.api.port;

/**
 * 业务关联执行摘要（E1-7：流程实例详情 / 表单记录详情反查展示）。
 *
 * @param executionId    受理行 id（跳转 Graph 执行详情）
 * @param graphKey       图 key
 * @param graphName      图名称
 * @param graphDefVersion 执行版本
 * @param status         终态/当前状态（QUEUED/RUNNING/SUCCESS/FAILED/CANCELLED/…）
 * @param businessSource 业务来源
 * @param latencyMs      耗时（毫秒）
 * @param errorCategory  失败分类
 * @param errorMessage   失败原因
 * @param createTime     受理时间（yyyy-MM-dd HH:mm:ss）
 */
public record AgentGraphExecutionBrief(
        Long executionId,
        String graphKey,
        String graphName,
        Integer graphDefVersion,
        String status,
        String businessSource,
        Long latencyMs,
        String errorCategory,
        String errorMessage,
        String createTime) {
}
