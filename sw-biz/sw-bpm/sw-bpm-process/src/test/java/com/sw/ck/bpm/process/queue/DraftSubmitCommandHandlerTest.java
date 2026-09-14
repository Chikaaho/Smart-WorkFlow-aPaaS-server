package com.sw.ck.bpm.process.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.BpmDraft;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.entity.DraftStatusEnum;
import com.sw.ck.bpm.process.service.BpmDraftService;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DraftSubmitCommandHandler} 单元测试。
 * <p>
 * 覆盖：幂等键、草稿状态流转（SUBMITTED / 幂等跳过 / FAILED 终态）。
 * </p>
 */
@DisplayName("草稿提交命令处理器测试")
class DraftSubmitCommandHandlerTest {

    private final BpmDraftService draftService = mock(BpmDraftService.class);
    private final FormDataSubmitFacade facade = mock(FormDataSubmitFacade.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DraftSubmitCommandHandler handler =
            new DraftSubmitCommandHandler(draftService, facade, objectMapper);

    private CommandEnvelope envelope(String draftId, int submitSeq) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(11L);
        envelope.setCommandType(CommandTypeEnum.DRAFT_SUBMIT);
        envelope.setTenantId(1L);
        envelope.setInitiatorId(2L);
        envelope.setCommandKey("DRAFT_SUBMIT:" + draftId + ":" + submitSeq);
        return envelope;
    }

    private BpmDraft draft(String id, String status) {
        BpmDraft draft = new BpmDraft();
        draft.setId(Long.valueOf(id));
        draft.setFormKey("leave_form");
        draft.setStatus(status);
        draft.setSubmitSeq(1);
        return draft;
    }

    @Test
    @DisplayName("正常：幂等键 DRAFT_SUBMIT:{draftId}:{submitSeq}；草稿转 SUBMITTED 且记录 recordId")
    void handle_shouldSubmitAndMarkDraftSubmitted() throws Exception {
        BpmDraft d = draft("5", DraftStatusEnum.EDITING.getCode());
        when(draftService.getById(5L)).thenReturn(d);
        when(facade.submit(anyString(), anyMap(), anyString(), nullable(String.class))).thenReturn("rec-009");
        CommandEnvelope envelope = envelope("5", 1);
        envelope.setPayload(objectMapper.writeValueAsString(Map.of(
                "formKey", "leave_form",
                "submitSeq", 1,
                "submittedData", Map.of("days", 2))));

        String result = handler.handle(envelope);

        assertThat(result).contains("SUBMITTED").contains("rec-009");
        verify(facade).submit(eq("leave_form"), anyMap(), eq("DRAFT_SUBMIT:5:1"), eq(null));
        ArgumentCaptor<BpmDraft> captor = ArgumentCaptor.forClass(BpmDraft.class);
        verify(draftService).updateById(captor.capture());
        BpmDraft updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(DraftStatusEnum.SUBMITTED.getCode());
        assertThat(updated.getResultRecordId()).isEqualTo("rec-009");
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    @DisplayName("草稿已 SUBMITTED → 幂等跳过，不调 facade")
    void handle_shouldSkipAlreadySubmittedDraft() throws Exception {
        BpmDraft d = draft("5", DraftStatusEnum.SUBMITTED.getCode());
        d.setResultRecordId("rec-001");
        when(draftService.getById(5L)).thenReturn(d);

        String result = handler.handle(envelope("5", 1));

        assertThat(result).contains("SKIP_SUBMITTED").contains("rec-001");
        verify(facade, never()).submit(anyString(), anyMap(), anyString(), anyString());
        verify(draftService, never()).updateById(any());
    }

    @Test
    @DisplayName("草稿不存在 → 抛异常")
    void handle_shouldThrowWhenDraftMissing() {
        when(draftService.getById(5L)).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(envelope("5", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("草稿不存在");
    }

    @Test
    @DisplayName("facade 抛异常 → 异常传播")
    void handle_shouldPropagateFacadeException() throws Exception {
        BpmDraft d = draft("5", DraftStatusEnum.EDITING.getCode());
        when(draftService.getById(5L)).thenReturn(d);
        when(facade.submit(anyString(), anyMap(), anyString(), nullable(String.class)))
                .thenThrow(new RuntimeException("表单服务不可用"));
        CommandEnvelope envelope = envelope("5", 1);
        envelope.setPayload(objectMapper.writeValueAsString(Map.of("formKey", "leave_form", "submitSeq", 1)));

        assertThatThrownBy(() -> handler.handle(envelope))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("表单服务不可用");
    }

    @Test
    @DisplayName("P0 草稿命令将 P0 通道传给表单 Facade")
    void handle_shouldPropagateP0ChannelToFormFacade() throws Exception {
        BpmDraft d = draft("5", DraftStatusEnum.EDITING.getCode());
        when(draftService.getById(5L)).thenReturn(d);
        when(facade.submit(anyString(), anyMap(), anyString(), anyString())).thenReturn("rec-p0");
        CommandEnvelope envelope = envelope("5", 1);
        envelope.setChannel(CommandChannelEnum.P0);
        envelope.setPayload(objectMapper.writeValueAsString(Map.of(
                "formKey", "leave_form", "submitSeq", 1, "submittedData", Map.of())));

        handler.handle(envelope);

        verify(facade).submit(eq("leave_form"), anyMap(), eq("DRAFT_SUBMIT:5:1"), eq("P0"));
    }

    @Test
    @DisplayName("onFinalFailure：草稿转 FAILED 且记录 lastError")
    void onFinalFailure_shouldMarkDraftFailed() {
        BpmDraft d = draft("5", DraftStatusEnum.SUBMITTING.getCode());
        when(draftService.getById(5L)).thenReturn(d);

        handler.onFinalFailure(envelope("5", 1), "始终失败");

        ArgumentCaptor<BpmDraft> captor = ArgumentCaptor.forClass(BpmDraft.class);
        verify(draftService).updateById(captor.capture());
        BpmDraft updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(DraftStatusEnum.FAILED.getCode());
        assertThat(updated.getLastError()).isEqualTo("始终失败");
    }
}
