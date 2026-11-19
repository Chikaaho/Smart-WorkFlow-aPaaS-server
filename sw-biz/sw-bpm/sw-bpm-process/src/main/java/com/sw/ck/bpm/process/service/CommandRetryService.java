package com.sw.ck.bpm.process.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 命令重试事务壳：requeueFailed 契约为 MANDATORY 事务，控制器入口经本服务开启事务调用。
 */
@Service
public class CommandRetryService {

    @Autowired
    private BpmCommandQueue commandQueue;

    @Transactional(rollbackFor = Exception.class)
    public Long requeueFailed(CommandEnvelope envelope) {
        return commandQueue.requeueFailed(envelope);
    }
}
