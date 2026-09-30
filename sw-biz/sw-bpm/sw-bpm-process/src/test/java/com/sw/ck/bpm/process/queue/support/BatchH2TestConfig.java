package com.sw.ck.bpm.process.queue.support;

import com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper;
import com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler;
import com.sw.ck.bpm.process.queue.CommandEffectRecorder;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.service.impl.TxnBatchServiceImpl;
import com.sw.ck.form.api.port.FormTxnActionPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.Mockito.mock;

/**
 * P62 S3 批量命令 H2 测试装配：复用队列隔离配置（真实 H2 + 真实迁移 V103 +
 * 真实租户/乐观锁拦截器），追加批量表 Mapper、受控 Port 桩与批量服务/处理器。
 */
@Configuration
@Import(QueueH2TestConfig.class)
public class BatchH2TestConfig {

    @Bean
    public FormTxnActionPort txnActionPort() {
        return mock(FormTxnActionPort.class);
    }

    @Bean
    public TxnBatchServiceImpl txnBatchService(BpmCommandBatchMapper batchMapper,
                                               BpmCommandBatchItemMapper itemMapper,
                                               PersistentBpmCommandQueue queue,
                                               FormTxnActionPort txnActionPort) {
        return new TxnBatchServiceImpl(batchMapper, itemMapper, queue, txnActionPort);
    }

    @Bean
    public BatchInvokeCommandHandler batchInvokeCommandHandler(BpmCommandBatchMapper batchMapper,
                                                               BpmCommandBatchItemMapper itemMapper,
                                                               FormTxnActionPort txnActionPort,
                                                               CommandEffectRecorder effectRecorder,
                                                               PlatformTransactionManager transactionManager) {
        return new BatchInvokeCommandHandler(batchMapper, itemMapper, txnActionPort,
                effectRecorder, transactionManager);
    }
}
