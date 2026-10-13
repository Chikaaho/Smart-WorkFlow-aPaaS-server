package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.port.FlowStartPortImpl;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActionRefRecoveryService} 受控恢复状态机测试（复审05 P1-06b）。
 * <p>
 * 覆盖：ORCH FAILED 复用同键；ORCH EXPIRED 生成恢复代新行；ORCH COMPLETED 二段窗口
 * 按持久状态分支（恢复/处理中/零目标安全处置）；目标实例已存在幂等拒绝。
 * </p>
 */
@DisplayName("动作意图受控恢复服务测试")
class ActionRefRecoveryServiceTest {

    private static final String ORCH_KEY = "P64ACT:TRG:pi-1:trg-1:NODE_ROUND_COMPLETED:1:act-1:11";

    private final BpmCommandQueue commandQueue = mock(BpmCommandQueue.class);
    private final CommandRetryService commandRetryService = mock(CommandRetryService.class);
    private final FlowStartPortImpl flowStartPort = mock(FlowStartPortImpl.class);
    private final BpmActionRefMapper actionRefMapper = mock(BpmActionRefMapper.class);
    private final BpmInstanceService bpmInstanceService = mock(BpmInstanceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ActionRefRecoveryService service = new ActionRefRecoveryService(
            commandQueue, commandRetryService, flowStartPort, actionRefMapper,
            bpmInstanceService, objectMapper);

    private BpmActionRef ref() {
        BpmActionRef ref = new BpmActionRef();
        ref.setId(1L);
        ref.setTenantId(9L);
        ref.setCommandKey(ORCH_KEY);
        ref.setTargetFormKey("target_form");
        ref.setTargetRecordId("rec-1");
        ref.setStatus("STARTING");
        return ref;
    }

    private CommandEnvelope envelope(String key, String status, String payload) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(77L);
        envelope.setCommandKey(key);
        envelope.setStatus(status);
        envelope.setPayload(payload);
        envelope.setTenantId(9L);
        envelope.setInitiatorId(2L);
        return envelope;
    }

    @Test
    @DisplayName("ORCH FAILED：复用同键命令重置入队，意图回 INTENT_SUBMITTED")
    void shouldRequeueFailedOrchCommand() {
        BpmActionRef ref = ref();
        when(bpmInstanceService.findByBusinessKey("rec-1")).thenReturn(Optional.empty());
        when(commandQueue.findByKey(9L, ORCH_KEY))
                .thenReturn(Optional.of(envelope(ORCH_KEY, CommandStatusEnum.FAILED.getCode(), null)));
        when(commandRetryService.requeueFailed(any())).thenReturn(77L);

        ActionRefRecoveryService.RetryOutcome outcome = service.retry(ref);

        assertThat(outcome.status()).isEqualTo("INTENT_SUBMITTED");
        assertThat(outcome.commandId()).isEqualTo(77L);
        assertThat(ref.getStatus()).isEqualTo("INTENT_SUBMITTED");
        verify(actionRefMapper).updateById(ref);
    }

    @Test
    @DisplayName("ORCH EXPIRED：登记恢复代新行（原行保留，载荷沿用）")
    void shouldEnqueueOrchGenerationWhenExpired() throws Exception {
        BpmActionRef ref = ref();
        when(bpmInstanceService.findByBusinessKey("rec-1")).thenReturn(Optional.empty());
        String payload = objectMapper.writeValueAsString(Map.of("actionId", "act-1"));
        when(commandQueue.findByKey(9L, ORCH_KEY))
                .thenReturn(Optional.of(envelope(ORCH_KEY, CommandStatusEnum.EXPIRED.getCode(), payload)));
        when(commandQueue.enqueue(any())).thenReturn(88L);

        ActionRefRecoveryService.RetryOutcome outcome = service.retry(ref);

        ArgumentCaptor<CommandEnvelope> captor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue).enqueue(captor.capture());
        assertThat(captor.getValue().getCommandKey()).isEqualTo(ORCH_KEY + ":R1");
        assertThat(captor.getValue().getPayload()).isEqualTo(payload);
        assertThat(outcome.status()).isEqualTo("INTENT_SUBMITTED");
        assertThat(outcome.commandId()).isEqualTo(88L);
        assertThat(ref.getStatus()).isEqualTo("INTENT_SUBMITTED");
    }

    @Test
    @DisplayName("ORCH COMPLETED + FLOW_START FAILED：按当前绑定登记恢复代，意图回 STARTING")
    void shouldRecoverFlowStartWhenOrchCompletedAndFlowFailed() throws Exception {
        BpmActionRef ref = ref();
        when(bpmInstanceService.findByBusinessKey("rec-1")).thenReturn(Optional.empty());
        when(commandQueue.findByKey(9L, ORCH_KEY))
                .thenReturn(Optional.of(envelope(ORCH_KEY, CommandStatusEnum.COMPLETED.getCode(), null)));
        String flowPayload = objectMapper.writeValueAsString(Map.of(
                "formKey", "target_form", "recordId", "rec-1", "submitter", "2",
                "submittedData", Map.of("k", "v")));
        when(commandQueue.findByKey(9L, "FLOW_START:rec-1"))
                .thenReturn(Optional.of(envelope("FLOW_START:rec-1",
                        CommandStatusEnum.FAILED.getCode(), flowPayload)));
        when(flowStartPort.recoverFlowStart(eq(9L), any())).thenReturn(Optional.of(5L));

        ActionRefRecoveryService.RetryOutcome outcome = service.retry(ref);

        assertThat(outcome.status()).isEqualTo("RECOVERY_ENQUEUED");
        assertThat(outcome.commandId()).isEqualTo(5L);
        assertThat(ref.getStatus()).isEqualTo("STARTING");
        assertThat(ref.getErrorText()).isNull();
        verify(actionRefMapper).updateById(ref);
    }

    @Test
    @DisplayName("ORCH COMPLETED + 无有效绑定：明确安全处置（零目标），不改写意图状态")
    void shouldReturnDisposedWhenNoActiveBinding() throws Exception {
        BpmActionRef ref = ref();
        when(bpmInstanceService.findByBusinessKey("rec-1")).thenReturn(Optional.empty());
        when(commandQueue.findByKey(9L, ORCH_KEY))
                .thenReturn(Optional.of(envelope(ORCH_KEY, CommandStatusEnum.COMPLETED.getCode(), null)));
        String flowPayload = objectMapper.writeValueAsString(Map.of(
                "formKey", "target_form", "recordId", "rec-1", "submitter", "2"));
        when(commandQueue.findByKey(9L, "FLOW_START:rec-1"))
                .thenReturn(Optional.of(envelope("FLOW_START:rec-1",
                        CommandStatusEnum.FAILED.getCode(), flowPayload)));
        when(flowStartPort.recoverFlowStart(eq(9L), any())).thenReturn(Optional.empty());

        ActionRefRecoveryService.RetryOutcome outcome = service.retry(ref);

        assertThat(outcome.status()).isEqualTo("DISPOSED_NO_BINDING");
        assertThat(outcome.message()).contains("零目标");
        assertThat(ref.getStatus()).isEqualTo("STARTING");
        verify(actionRefMapper, never()).updateById(any(BpmActionRef.class));
    }

    @Test
    @DisplayName("FLOW_START 仍在处理中：返回进行中诊断，不产生新行")
    void shouldDiagnoseProcessingWhenFlowStartPending() throws Exception {
        BpmActionRef ref = ref();
        when(bpmInstanceService.findByBusinessKey("rec-1")).thenReturn(Optional.empty());
        when(commandQueue.findByKey(9L, ORCH_KEY))
                .thenReturn(Optional.of(envelope(ORCH_KEY, CommandStatusEnum.COMPLETED.getCode(), null)));
        when(commandQueue.findByKey(9L, "FLOW_START:rec-1"))
                .thenReturn(Optional.of(envelope("FLOW_START:rec-1",
                        CommandStatusEnum.PENDING.getCode(), "{}")));

        ActionRefRecoveryService.RetryOutcome outcome = service.retry(ref);

        assertThat(outcome.status()).isEqualTo("PROCESSING");
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("目标实例已存在：幂等拒绝，不查命令不做恢复")
    void shouldRejectWhenTargetInstanceExists() {
        BpmActionRef ref = ref();
        when(bpmInstanceService.findByBusinessKey("rec-1"))
                .thenReturn(Optional.of(new BpmInstance()));

        ActionRefRecoveryService.RetryOutcome outcome = service.retry(ref);

        assertThat(outcome.status()).isEqualTo("ALREADY_STARTED");
        verify(commandQueue, never()).findByKey(any(), any());
    }
}
