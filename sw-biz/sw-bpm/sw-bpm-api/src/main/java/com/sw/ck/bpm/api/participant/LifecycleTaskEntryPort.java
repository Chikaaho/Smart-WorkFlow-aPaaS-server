package com.sw.ck.bpm.api.participant;

import java.util.List;
import java.util.Map;

/**
 * 人工任务生命周期接缝（I3，api 契约）。
 * <p>
 * 引擎在人工任务 create 时回调实现（业务侧），由实现完成：
 * 授权代理规则匹配与代理改派、办理时限账本登记。实现由 sw-bpm-process 提供；
 * 引擎仅面向本接口，不依赖业务模块实现细节。
 * </p>
 */
public interface LifecycleTaskEntryPort {

    /**
     * 任务进入生命周期。
     *
     * @param tenantId          租户
     * @param processInstanceId 实例
     * @param nodeKey           节点
     * @param taskId            任务
     * @param resolvedUsers     解析出的参与人（当前唯一的候选人）
     * @param nodeConfig        节点配置（可能为 null）
     * @return 代理改写后的 assignee 用户 ID；null 表示不改写
     */
    String onTaskCreate(Long tenantId, String processInstanceId, String nodeKey,
                        String taskId, List<String> resolvedUsers, String nodeConfig);

    /**
     * 节点函数参与人解析（I3 §4.9）：节点配置声明 RESOLVE_PARTICIPANTS 函数时，
     * 输出覆盖策略解析结果；返回 null 表示未配置函数（走默认策略）。
     */
    java.util.List<String> resolveParticipantsByFunction(Long tenantId, String processInstanceId,
                                                         String nodeKey, String taskId,
                                                         Map<String, Object> variables,
                                                         String nodeConfig);
}
