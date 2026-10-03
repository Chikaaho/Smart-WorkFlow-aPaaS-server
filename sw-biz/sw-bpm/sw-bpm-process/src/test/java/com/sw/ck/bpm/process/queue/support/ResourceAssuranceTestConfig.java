package com.sw.ck.bpm.process.queue.support;

import com.sw.ck.form.api.port.FormTxnActionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            public TxnActionResult invoke(TxnActionCommand command) {
                return new TxnActionResult("inv-" + command.invocationKey(), "SUCCEEDED",
                        1, null, null, null, null, null, null, 1L, false);
            }

            @Override
            public java.util.Optional<TxnActionDescriptor> describe(String actionId) {
                return java.util.Optional.of(new TxnActionDescriptor(actionId, "form", "key",
                        "RESERVE", "PUBLISHED", 1));
            }

            @Override
            public java.util.Optional<FormTxnActionPort.TxnInvocationSummary> findInvocationByKey(String invocationKey) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.List<FormTxnActionPort.TxnInvocationSummary> listInvocationsByBizRecord(
                    String bizRecordId, int limit) {
                return java.util.List.of();
            }
        };
    }

    @Bean
    public com.sw.ck.bpm.process.service.impl.TxnBatchServiceImpl resourceTestTxnBatchService(
            com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper batchMapper,
            com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper itemMapper,
            com.sw.ck.bpm.process.queue.BpmCommandQueue commandQueue,
            FormTxnActionPort stubFormTxnActionPort,
            com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService) {
        return new com.sw.ck.bpm.process.service.impl.TxnBatchServiceImpl(batchMapper, itemMapper,
                commandQueue, stubFormTxnActionPort, admissionService);
    }

    @Bean
    public com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler resourceTestBatchInvokeCommandHandler(
            com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper batchMapper,
            com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper itemMapper,
            com.sw.ck.bpm.process.queue.BpmCommandQueue commandQueue,
            FormTxnActionPort stubFormTxnActionPort,
            com.sw.ck.bpm.process.queue.CommandEffectRecorder effectRecorder,
            org.springframework.transaction.PlatformTransactionManager txManager,
            com.sw.ck.bpm.process.service.ResourceReleaseService releaseService,
            com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService) {
        return new com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler(batchMapper, itemMapper,
                commandQueue, stubFormTxnActionPort, effectRecorder, txManager, releaseService,
                admissionService);
    }
}
