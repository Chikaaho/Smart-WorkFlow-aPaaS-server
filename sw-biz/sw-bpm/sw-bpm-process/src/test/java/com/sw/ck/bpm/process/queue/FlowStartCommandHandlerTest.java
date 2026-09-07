package com.sw.ck.bpm.process.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.ProcessStartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FlowStartCommandHandler} 单元测试。
 * <p>
 * 覆盖：payload → StartCommand 字段映射；businessKey 已有实例时幂等跳过。
 * </p>
 */
@DisplayName("流程发起命令处理器测试")
class FlowStartCommandHandlerTest {

    private final ProcessStartService processStartService = mock(ProcessStartService.class);
    private final BpmInstanceService bpmInstanceService = mock(BpmInstanceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final FlowStartCommandHandler handler =
            new FlowStartCommandHandler(processStartService, bpmInstanceService, objectMapper);

    @Test
    @DisplayName("types 声明 FLOW_START")
    void types_shouldDeclareFlowStart() {
        assertThat(handler.types()).containsExactly(CommandTypeEnum.FLOW_START);
    }

    @Test
    @DisplayName("正常 payload → start 收到正确 StartCommand（formKey/recordId/submitter/tenantId/submittedData）")
    void handle_shouldStartProcessWithMappedCommand() throws Exception {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(1L);
        envelope.setCommandType(CommandTypeEnum.FLOW_START);
        envelope.setTenantId(9L);
        envelope.setInitiatorId(7L);
        envelope.setPayload(objectMapper.writeValueAsString(Map.of(
                "formKey", "leave_form",
                "recordId", "rec-001",
                "submitter", "7",
                "submittedData", Map.of("days", 3))));
        when(bpmInstanceService.findByBusinessKey("rec-001")).thenReturn(Optional.empty());

        String result = handler.handle(envelope);

        assertThat(result).contains("STARTED");
        ArgumentCaptor<StartCommand> captor = ArgumentCaptor.forClass(StartCommand.class);
        verify(processStartService).start(captor.capture());
        StartCommand cmd = captor.getValue();
        assertThat(cmd.getFormKey()).isEqualTo("leave_form");
        assertThat(cmd.getRecordId()).isEqualTo("rec-001");
        assertThat(cmd.getSubmitter()).isEqualTo(7L);
        assertThat(cmd.getTenantId()).isEqualTo(9L);
        assertThat(cmd.getSubmittedData()).containsEntry("days", 3);
    }

    @Test
    @DisplayName("businessKey 已有实例 → 不调 start，结果含 SKIP_DUPLICATE")
    void handle_shouldSkipWhenBusinessKeyExists() throws Exception {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.FLOW_START);
        envelope.setPayload(objectMapper.writeValueAsString(Map.of(
                "formKey", "leave_form", "recordId", "rec-001")));
        when(bpmInstanceService.findByBusinessKey("rec-001"))
                .thenReturn(Optional.of(new BpmInstance()));

        String result = handler.handle(envelope);

        assertThat(result).contains("SKIP_DUPLICATE");
        verify(processStartService, never()).start(any());
    }
}
