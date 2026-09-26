package com.sw.ck.job.port;

import com.sw.ck.job.event.ScheduledFlowTriggerEvent;

import java.util.Optional;

/**
 * 定时任务流程启动受理端口（job-api 定义，bpm 模块实现）。
 *
 * <p>FLOW 类型定时任务的可靠交付边界：调度器在<b>自身事务内</b>调用本端口受理一次启动意图，
 * 实现方必须把意图持久化到可恢复的持久结构（当前为持久命令队列），
 * 由领取者以 {@code jobId + fireTime} 为幂等键最终启动一次流程。</p>
 *
 * <p>本端口替代“发布进程内事件 + 期望某个监听者存在”的旧路径：
 * 旧路径在无监听者、无事务或进程退出时会静默丢弃任务，且 Job 日志仍记为成功。</p>
 */
public interface ScheduledFlowStartPort {

    /**
     * 受理一次定时流程启动意图（在调用方事务内持久化，不得执行外部 I/O）。
     *
     * @param event 启动意图（jobId/flowDefKey/formData/fireTime/tenantId/configuredBy），不可为空
     * @return present = 持久命令 ID（幂等：同一 {@code jobId + fireTime} 重复受理返回既有命令 ID）；
     *         empty = 该流程键当前无可用绑定，属合法 no-op（未持久化意图，调用方不得记为成功）；
     *         当前契约恒 present 或 empty，真实错误抛明确异常
     */
    Optional<Long> acceptScheduledFlowStart(ScheduledFlowTriggerEvent event);
}
