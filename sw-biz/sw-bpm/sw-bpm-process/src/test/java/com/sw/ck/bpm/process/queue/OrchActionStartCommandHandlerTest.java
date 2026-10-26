package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
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
 * （SKIP_DUPLICATE 不重复启动）；记录归属接缝（复审05 P2-04a）——目标定义首节点
 * FORM_FIELD/USER/MAIN 参与人解析为归属人走 submitOnBehalfOf，非该配置保持原语义。
 * </p>
 */
@DisplayName("P64 关联动作命令处理器测试")
class OrchActionStartCommandHandlerTest {

    private static final String COMMAND_KEY = "P64ACT:x:act-1:11";
    private static final String IDEM_KEY = OrchActionStartCommandHandler.formIdempotencyKey(COMMAND_KEY);

    private final BpmActionRefMapper actionRefMapper = mock(BpmActionRefMapper.class);
    private final FormDataSubmitFacade formDataSubmitFacade = mock(FormDataSubmitFacade.class);
    private final BpmProcessDefService processDefService = mock(BpmProcessDefService.class);
    private final NodeFormDataService nodeFormDataService = mock(NodeFormDataService.class);
    private final OrchActionStartCommandHandler handler = new OrchActionStartCommandHandler(
            actionRefMapper, formDataSubmitFacade, processDefService, nodeFormDataService);

    private CommandEnvelope envelope(String payload) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(9L);
        envelope.setCommandType(CommandTypeEnum.ORCH_ACTION_START);
        envelope.setCommandKey(COMMAND_KEY);
        envelope.setTenantId(9L);
        envelope.setInitiatorId(7L);
        envelope.setPayload(payload);
        return envelope;
    }

    private BpmActionRef ref(long id, String status, String recordId) {
        BpmActionRef ref = new BpmActionRef();
        ref.setId(id);
        ref.setCommandKey(COMMAND_KEY);
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
    @DisplayName("正常消费：同事务建目标记录并回填 STARTING（受理启动中，实例未建不冒称已启动）")
    void shouldCreateRecordAndMarkStarted() throws Exception {
        when(actionRefMapper.selectById(1L)).thenReturn(ref(1L, "INTENT_SUBMITTED", null));
        String payload = "{\"refId\":1,\"targetFormKey\":\"target_form\",\"data\":{\"owner\":\"11\"}}";
        when(formDataSubmitFacade.submitOnBehalfOf(eq("target_form"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                eq(IDEM_KEY), eq(7L)))
                .thenAnswer(invocation -> {
                    Map<String, Object> submitted = invocation.getArgument(1);
                    assertThat(submitted).containsEntry("owner", "11");
                    return Optional.of("rec-target-1");
                });

        String result = handler.handle(envelope(payload));

        assertThat(result).contains("STARTED").contains("rec-target-1");
        verify(actionRefMapper).updateById(ref(1L, "STARTING", "rec-target-1"));
    }

    @Test
    @DisplayName("表单幂等键：commandKey 摘要定长 ≤128，且不同项可区分")
    void formIdempotencyKeyIsBoundedAndDistinct() {
        String longSingle = "P64ACT:" + "x".repeat(300) + ":SINGLE";
        String longRow = "P64ACT:" + "x".repeat(300) + ":ROW#1";
        assertThat(OrchActionStartCommandHandler.formIdempotencyKey(longSingle))
                .hasSizeLessThanOrEqualTo(128);
        assertThat(OrchActionStartCommandHandler.formIdempotencyKey(longSingle))
                .isNotEqualTo(OrchActionStartCommandHandler.formIdempotencyKey(longRow));
        assertThat(OrchActionStartCommandHandler.formIdempotencyKey(COMMAND_KEY)).isEqualTo(IDEM_KEY);
    }

    @Test
    @DisplayName("同身份重放：回查原结果 SKIP_DUPLICATE，不重复建记录")
    void shouldReplayOriginalResultOnDuplicate() throws Exception {
        when(actionRefMapper.selectById(1L)).thenReturn(ref(1L, "STARTED", "rec-target-1"));

        String result = handler.handle(envelope("{\"refId\":1}"));

        assertThat(result).contains("SKIP_DUPLICATE").contains("rec-target-1");
        org.mockito.Mockito.verifyNoInteractions(formDataSubmitFacade);
    }

    @Test
    @DisplayName("启动中窗口重放：STARTING 且目标记录已建即跳过，FLOW_START 未完成/失败不重复建目标")
    void shouldSkipReplayWhileTargetStarting() throws Exception {
        when(actionRefMapper.selectById(1L)).thenReturn(ref(1L, "STARTING", "rec-target-1"));

        String result = handler.handle(envelope("{\"refId\":1}"));

        assertThat(result).contains("SKIP_DUPLICATE").contains("rec-target-1");
        verify(actionRefMapper, org.mockito.Mockito.never())
                .updateById(org.mockito.ArgumentMatchers.any(BpmActionRef.class));
        org.mockito.Mockito.verifyNoInteractions(formDataSubmitFacade);
    }

    @Test
    @DisplayName("失败后恢复：首次受理失败不落记录，重投后同幂等键补齐")
    void shouldRecoverAfterFailureWithSameIdempotencyKey() throws Exception {
        when(actionRefMapper.selectById(1L)).thenReturn(ref(1L, "INTENT_SUBMITTED", null));
        String payload = "{\"refId\":1,\"targetFormKey\":\"target_form\",\"data\":{\"owner\":\"11\"}}";
        // 首次消费：目标记录创建失败（抛出 → 命令层按既有 failAndScheduleRetry 重试）
        when(formDataSubmitFacade.submitOnBehalfOf(eq("target_form"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                eq(IDEM_KEY), eq(7L)))
                .thenThrow(new IllegalStateException("目标表单暂不可用"))
                .thenAnswer(invocation -> Optional.of("rec-target-1"));

        assertThatThrownBy(() -> handler.handle(envelope(payload)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目标表单暂不可用");
        // 恢复重投：同 commandKey 幂等键重试成功；ref 收敛 STARTING（实例由消费链异步创建）
        String result = handler.handle(envelope(payload));

        assertThat(result).contains("STARTED").contains("rec-target-1");
        verify(actionRefMapper).updateById(ref(1L, "STARTING", "rec-target-1"));
        // 同一幂等键共受理两次（1 失败 + 1 成功），成功路径只回填一条目标记录
        verify(formDataSubmitFacade, org.mockito.Mockito.times(2)).submitOnBehalfOf(
                eq("target_form"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                eq(IDEM_KEY), eq(7L));
    }

    // ==================== 复审05 P2-04a：系统派发记录归属接缝 ====================

    private BpmProcessDef publishedDef(String processKey) {
        BpmProcessDef def = new BpmProcessDef();
        def.setProcessKey(processKey);
        def.setPublishedVersion(3);
        return def;
    }

    private ProcessGraph graphWithFirstNodeParticipant(Map<String, Object> participant) {
        Map<String, Object> nodeConfig = new HashMap<>();
        if (participant != null) {
            nodeConfig.put("participant", participant);
        }
        return ProcessGraph.builder().elements(List.of(
                GraphElement.builder().id("start").kind("node").type("START").build(),
                GraphElement.builder().id("node_first").kind("node").type("APPROVAL")
                        .config(nodeConfig).build(),
                GraphElement.builder().id("e1").kind("edge").source("start").target("node_first").build())).build();
    }

    @Test
    @DisplayName("记录归属接缝：首节点 FORM_FIELD/USER/MAIN 参与人 → 按字段值走 submitOnBehalfOf（create_by=被分配办理人）")
    void shouldSubmitOnBehalfOfResolvedFormFieldOwner() throws Exception {
        when(actionRefMapper.selectById(1L)).thenReturn(ref(1L, "INTENT_SUBMITTED", null));
        long ownerId = 2108817586892435457L;
        String payload = "{\"refId\":1,\"targetFormKey\":\"target_form\",\"data\":{\"owner_id\":\""
                + ownerId + "\"}}";
        BpmProcessDef def = publishedDef("def_target");
        when(processDefService.findByProcessKey("def_target")).thenReturn(def);
        Map<String, Object> binding = Map.of("strategy", "FORM_FIELD",
                "value", Map.of("objectType", "USER", "scope", "MAIN", "field", "owner_id"));
        when(nodeFormDataService.loadGraph("def_target", 3))
                .thenReturn(graphWithFirstNodeParticipant(binding));
        when(formDataSubmitFacade.submitOnBehalfOf(eq("target_form"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                eq(IDEM_KEY), eq(ownerId)))
                .thenReturn(Optional.of("rec-target-1"));

        String result = handler.handle(envelope(payload));

        assertThat(result).contains("STARTED").contains("rec-target-1");
        verify(formDataSubmitFacade, org.mockito.Mockito.never())
                .submit(anyString(), org.mockito.ArgumentMatchers.anyMap(), anyString());
        verify(actionRefMapper).updateById(ref(1L, "STARTING", "rec-target-1"));
    }

    @Test
    @DisplayName("非 FORM_FIELD/USER/MAIN 参与人：归属回落到源实例发起人（不落到调度系统用户）")
    void shouldFallbackToPlainSubmitWhenParticipantNotFormFieldUserMain() throws Exception {
        when(actionRefMapper.selectById(2L)).thenReturn(ref(2L, "INTENT_SUBMITTED", null));
        String payload = "{\"refId\":2,\"targetFormKey\":\"target_form\",\"data\":{\"owner_id\":\"x\"}}";
        // TABLE 范围参与人：首节点在记录创建时刻无单一归属人 → 回落源实例发起人
        BpmProcessDef def = publishedDef("def_target");
        when(processDefService.findByProcessKey("def_target")).thenReturn(def);
        Map<String, Object> tableBinding = Map.of("strategy", "FORM_FIELD",
                "value", Map.of("objectType", "USER", "scope", "TABLE",
                        "tableField", "t", "column", "handler"));
        when(nodeFormDataService.loadGraph("def_target", 3))
                .thenReturn(graphWithFirstNodeParticipant(tableBinding));
        when(formDataSubmitFacade.submitOnBehalfOf(eq("target_form"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                eq(IDEM_KEY), eq(7L)))
                .thenReturn(Optional.of("rec-target-2"));

        String result = handler.handle(envelope(payload));

        assertThat(result).contains("STARTED").contains("rec-target-2");
        verify(formDataSubmitFacade, org.mockito.Mockito.never())
                .submit(anyString(), org.mockito.ArgumentMatchers.anyMap(), anyString());
    }

    @Test
    @DisplayName("定义未发布/图缺失：归属回落到源实例发起人，不阻断派发")
    void shouldFallbackWhenTargetDefUnresolvable() throws Exception {
        when(actionRefMapper.selectById(3L)).thenReturn(ref(3L, "INTENT_SUBMITTED", null));
        String payload = "{\"refId\":3,\"targetFormKey\":\"target_form\",\"data\":{\"owner_id\":\"1\"}}";
        when(processDefService.findByProcessKey("def_target")).thenReturn(null);
        when(formDataSubmitFacade.submitOnBehalfOf(eq("target_form"),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                eq(IDEM_KEY), eq(7L)))
                .thenReturn(Optional.of("rec-target-3"));

        String result = handler.handle(envelope(payload));

        assertThat(result).contains("STARTED").contains("rec-target-3");
        verify(formDataSubmitFacade, org.mockito.Mockito.never())
                .submit(anyString(), org.mockito.ArgumentMatchers.anyMap(), anyString());
    }
}
