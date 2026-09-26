package com.sw.ck.iot.api;

import java.util.Optional;

/**
 * IoT 流程触发结果回写 Facade（bpm-process → iot，经 Spring 注入）。
 *
 * <h3>present / empty 两层语义</h3>
 * <ul>
 *   <li><b>{@code Optional.of(true)}</b>：本次调用改写了触发记录终态（写入 SUCCESS 或 FAILED）。</li>
 *   <li><b>{@code Optional.of(false)}</b>：命中终态保护，触发记录已处于终态而未被改写——
 *       这是<b>合法幂等/零变更，属于"有值的结果"</b>，不得伪装成 {@code Optional.empty()}。
 *       迟到写回、恢复调度重投都会落到这一分支。</li>
 *   <li><b>{@code Optional.empty()}</b>：无该幂等键的触发记录（查询目标不存在）。</li>
 * </ul>
 * <p>写库失败等基础设施错误继续抛出，不得吞为 empty。</p>
 */
public interface IotProcessTriggerFacade {

    /**
     * 回写流程发起结果（幂等键定位）。
     *
     * @param idempotentKey     幂等键
     * @param processInstanceId 流程实例 ID（成功时）
     * @param error             失败原因（失败时）
     * @return true = 本次改写了终态；false = 命中终态保护未改写；empty = 无该幂等键的触发记录
     */
    Optional<Boolean> markTriggerResult(String idempotentKey, String processInstanceId, String error);
}
