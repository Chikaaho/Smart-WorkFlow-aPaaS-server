package com.sw.ck.iot.api;

/**
 * IoT 流程触发结果回写 Facade（bpm → iot，经 Spring 注入）。
 */
public interface IotProcessTriggerFacade {

    /**
     * 回写流程发起结果（幂等键定位）。
     *
     * @param idempotentKey     幂等键
     * @param processInstanceId 流程实例 ID（成功时）
     * @param error             失败原因（失败时）
     */
    void markTriggerResult(String idempotentKey, String processInstanceId, String error);
}
