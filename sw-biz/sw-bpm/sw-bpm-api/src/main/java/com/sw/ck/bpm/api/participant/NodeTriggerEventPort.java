package com.sw.ck.bpm.api.participant;

/**
 * BF20261013-022：节点/流程生命周期触发事件端口（engine → process 回调）。
 * <p>
 * 引擎在任务创建（审批节点开始、办理前）与流程实例创建成功后回调，由 process 侧
 * 评估对应触发器（NODE_ENTERING / PROCESS_START）。实现随 process 模块装配，
 * 引擎经 ObjectProvider 可选注入（引擎单测无实现时跳过）。回调内的触发器评估
 * 失败不抛出（执行记录落可诊断行），不得阻断任务创建/流程发起主链路。
 * </p>
 */
public interface NodeTriggerEventPort {

    /**
     * 节点开始（BF20261013-022①「开始前」）：审批任务创建成功后、办理人未见任务前评估。
     *
     * @param processInstanceId 流程实例 ID
     * @param nodeKey           节点 key（任务 definition key）
     */
    void onNodeEntering(String processInstanceId, String nodeKey);

    /**
     * 流程发起（BF20261013-022⑥「流程发起」触发器）：实例创建成功后评估。
     *
     * @param processInstanceId 流程实例 ID
     */
    void onProcessStarted(String processInstanceId);
}
