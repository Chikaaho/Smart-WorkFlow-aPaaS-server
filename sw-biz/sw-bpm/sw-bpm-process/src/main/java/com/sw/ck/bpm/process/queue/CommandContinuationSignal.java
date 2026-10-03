package com.sw.ck.bpm.process.queue;

/**
 * 命令续跑信号（P62 资源保障）：处理器在分片执行后主动让出调度权时抛出。
 * <p>
 * 语义：命令已被处理器重新入队（PENDING、立即重领、不计失败重试、占用不变），
 * 调度器收到本信号后不得 complete/fail——本次消费尚未产生终态，剩余分片由
 * 后续轮询继续。用于后台批量切片：单批长时间占据调度线程会让同车道其他工作
 * （含保留容量保护对象）失去推进机会，切片让出保证每轮共享预算内有界推进。
 * </p>
 */
public class CommandContinuationSignal extends RuntimeException {

    public CommandContinuationSignal(String message) {
        super(message);
    }
}
