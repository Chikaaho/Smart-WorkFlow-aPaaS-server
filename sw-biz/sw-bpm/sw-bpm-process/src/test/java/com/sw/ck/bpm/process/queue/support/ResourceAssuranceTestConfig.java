package com.sw.ck.bpm.process.queue.support;

import com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper;
import com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEffectRecorder;
import com.sw.ck.bpm.process.service.ResourceAdmissionService;
import com.sw.ck.bpm.process.service.ResourceReleaseService;
import com.sw.ck.bpm.process.service.impl.TxnBatchServiceImpl;
import com.sw.ck.form.api.port.FormTxnActionPort;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

/**
 * 资源保障测试装配：在队列隔离配置之上追加受控 Port 桩与批量命令处理器——
 * 供策略启用检查核验「批量消费者已注册」与批量切片行为验证使用。
 * 与批量命令专项装配（{@link BatchH2TestConfig}，mock Port）互斥使用，不并存。
 */
@Configuration
public class ResourceAssuranceTestConfig {

    @Bean
    public FormTxnActionPort stubFormTxnActionPort() {
        return new FormTxnActionPort() {
            @Override
            public Optional<TxnActionResult> invoke(TxnActionCommand command) {
                return Optional.of(new TxnActionResult("inv-" + command.invocationKey(),
                        "SUCCEEDED", 1, null, null, null, null, null, null, 1L, false));
            }

            @Override
            public Optional<TxnActionDescriptor> describe(String actionId) {
                return Optional.of(new TxnActionDescriptor(actionId, "form", "key",
                        "RESERVE", "PUBLISHED", 1));
            }

            @Override
            public Optional<FormTxnActionPort.TxnInvocationSummary> findInvocationByKey(String invocationKey) {
                return Optional.empty();
            }

            @Override
            public Optional<List<FormTxnActionPort.TxnInvocationSummary>> listInvocationsByBizRecord(
                    String bizRecordId, int limit) {
                return Optional.of(List.of());
            }
        };
    }

    @Bean
    public TxnBatchServiceImpl resourceTestTxnBatchService(
            BpmCommandBatchMapper batchMapper,
            BpmCommandBatchItemMapper itemMapper,
            BpmCommandQueue commandQueue,
            FormTxnActionPort stubFormTxnActionPort,
            ResourceAdmissionService admissionService) {
        return new TxnBatchServiceImpl(batchMapper, itemMapper,
                commandQueue, stubFormTxnActionPort, admissionService);
    }

    @Bean
    public BatchInvokeCommandHandler resourceTestBatchInvokeCommandHandler(
            BpmCommandBatchMapper batchMapper,
            BpmCommandBatchItemMapper itemMapper,
            BpmCommandQueue commandQueue,
            FormTxnActionPort stubFormTxnActionPort,
            CommandEffectRecorder effectRecorder,
            PlatformTransactionManager txManager,
            ResourceReleaseService releaseService,
            ResourceAdmissionService admissionService) {
        return new BatchInvokeCommandHandler(batchMapper, itemMapper,
                commandQueue, stubFormTxnActionPort, effectRecorder, txManager, releaseService,
                admissionService);
    }
}
