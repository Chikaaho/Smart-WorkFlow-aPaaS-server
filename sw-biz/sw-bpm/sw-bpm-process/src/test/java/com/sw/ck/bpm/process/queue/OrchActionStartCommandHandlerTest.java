package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrchActionStartCommandHandler} 单元测试（ADR-P64-001 §4）。
 * <p>
 * 覆盖：意图行缺失拒绝；正常启动（建记录 + STARTED 回填）；同身份重放回查原结果
 * （SKIP_DUPLICATE 不重复启动）。
 * </p>
 */
@DisplayName("P64 关联动作命令处理器测试")
class OrchActionStartCommandHandlerTest {

    private final BpmActionRefMapper actionRefMapper = mock(BpmActionRefMapper.class);
    private final FormDataSubmitFacade formDataSubmitFacade = mock(FormDataSubmitFacade.class);
    private final OrchActionStartCommandHandler handler =
            new OrchActionStartCommandHandler(actionRefMapper, formDataSubmitFacade);

    private CommandEnvelope envelope(String payload) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(9L);
        envelope.setCommandType(CommandTypeEnum.ORCH_ACTION_START);
        envelope.setCommandKey("P64ACT:x:act-1:11");
        envelope.setTenantId(9L);
        envelope.setInitiatorId(7L);
        envelope.setPayload(payload);
        return envelope;
    }

    private BpmActionRef ref(long id, String status, String recordId) {
        BpmActionRef ref = new BpmActionRef();
        ref.setId(id);
        ref.setCommandKey("P64ACT:x:act-1:11");
        ref.setTargetFormKey("target_form");
        ref.setTargetDefKey("def_target");
        ref.setStatus(status);
        ref.setTargetRecordId(recordId);
        return ref;
    }

    @Test
    @DisplayName("意图行缺失：明确失败，不冒称成功")
    void shouldFailWhenRefRowMissing() {
        when(actionRefMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> handler.handle(envelope("{\"refId\":1}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("动作意图行缺失");
    }

    @Test
    @DisplayName("正常消费：同事务建目标记录并回填 STARTED")
    void shouldCreateRecordAndMarkStarted() throws Exception {
        when(actionRefMapper.selectById(1L)).thenReturn(ref(1L, "INTENT_SUBMITTED", null));
        String payload = "{\"refId\":1,\"targetFormKey\":\"target_form\",\"data\":{\"owner\":\"11\"}}";
        when(formDataSubmitFacade.submit(eq("target_form"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                eq("P64ACT:x:act-1:11")))
                .thenAnswer(invocation -> {
                    Map<String, Object> submitted = invocation.getArgument(1);
                    assertThat(submitted).containsEntry("owner", "11");
                    return Optional.of("rec-target-1");
                });

        String result = handler.handle(envelope(payload));

        assertThat(result).contains("STARTED").contains("rec-target-1");
        verify(actionRefMapper).updateById(ref(1L, "STARTED", "rec-target-1"));
    }

    @Test
    @DisplayName("同身份重放：回查原结果 SKIP_DUPLICATE，不重复建记录")
    void shouldReplayOriginalResultOnDuplicate() throws Exception {
        when(actionRefMapper.selectById(1L)).thenReturn(ref(1L, "STARTED", "rec-target-1"));

        String result = handler.handle(envelope("{\"refId\":1}"));

        assertThat(result).contains("SKIP_DUPLICATE").contains("rec-target-1");
        org.mockito.Mockito.verifyNoInteractions(formDataSubmitFacade);
    }
}
