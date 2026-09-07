package com.sw.ck.bpm.process.queue;

/**
 * 命令处理器 SPI：每种 {@link com.sw.ck.bpm.process.entity.CommandTypeEnum}
 * 一个实现，由 {@link CommandDispatcher} 按类型分发。
 * <p>
 * 实现要求：以信封中的 tenantId/initiatorId 还原可信业务上下文，自行复核当时
 * 有效的权限与目标状态；处理逻辑可重入（同一命令重复消费不得产生重复业务结果）。
 * </p>
 */
public interface BpmCommandHandler {

    /** 声明处理的命令类型（可多个）。 */
    java.util.Set<com.sw.ck.bpm.process.entity.CommandTypeEnum> types();

    /**
     * 处理命令。
     *
     * @return 结果 JSON（写入受理记录供回查）
     * @throws Exception 处理失败（由调度器按退避策略安排重试）
     */
    String handle(CommandEnvelope envelope) throws Exception;

    /**
     * 有界重试耗尽、命令进入 FAILED 终态后的补偿回调（默认空实现）。
     */
    default void onFinalFailure(CommandEnvelope envelope, String reason) {
    }
}
