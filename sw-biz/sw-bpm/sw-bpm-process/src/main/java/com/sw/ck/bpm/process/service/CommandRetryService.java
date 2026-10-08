package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 命令重试事务壳：requeueFailed 契约为 MANDATORY 事务，控制器入口经本服务开启事务调用。
 */
@Service
public class CommandRetryService {

    private final BpmCommandQueue commandQueue;

    public CommandRetryService(BpmCommandQueue commandQueue) {
        this.commandQueue = commandQueue;
    }

    @Transactional
    public Long requeueFailed(CommandEnvelope envelope) {
        return commandQueue.requeueFailed(envelope);
    }
}
