package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 0.3.1 意见驱动（d）：节点级触发器脚本注入 {@code nodeOpinions} 快照。
 * <p>
 * 覆盖：本节点本流水轮全部意见注入（actorId/taskId/action/fields）、他节点/他轮/空意见
 * 记录过滤、意见记录服务缺席时原样返回。
 * </p>
 */
@DisplayName("触发器脚本 nodeOpinions 快照注入（0.3.1 意见驱动 d）")
class TriggerExecutionServiceNodeOpinionsTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ApprovalActionService> actionServiceProvider =
            mock(ObjectProvider.class);
    private final ApprovalActionService actionService = mock(ApprovalActionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TriggerExecutionService service() {
        return new TriggerExecutionService(null, null, null, null, null, null, null,
                objectMapper, null, null, null, actionServiceProvider);
    }

    private ApprovalActionRecord record(String nodeKey, Integer roundNo, Long actorId,
                                        String action, String opinionData) {
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setNodeKey(nodeKey);
        record.setRoundNo(roundNo);
        record.setActorId(actorId);
        record.setAction(action);
        record.setTaskId("task-" + actorId);
        record.setOpinionData(opinionData);
        return record;
    }

    @Test
    @DisplayName("本节点本流水轮全部意见注入 nodeOpinions（actorId/taskId/action/fields）")
    void shouldInjectNodeOpinionsForSameNode() {
        when(actionServiceProvider.getIfAvailable()).thenReturn(actionService);
        when(actionService.findByProcessInstanceId("pi-1")).thenReturn(List.of(
                record("node_tech", 1, 11L, "APPROVE",
                        "{\"riskRating\":\"HIGH\",\"downtimeMinutes\":180,\"techOpinion\":\"见附页\"}"),
                record("node_tech", 1, 12L, "APPROVE",
                        "{\"riskRating\":\"MEDIUM\",\"downtimeMinutes\":300}"),
                record("node_other", 1, 13L, "APPROVE", "{\"x\":1}"),
                record("node_tech", 2, 14L, "APPROVE", "{\"y\":1}"),
                record("node_tech", 1, 15L, "APPROVE", null)));

        ScriptFunctionContext result = service().withNodeOpinions(
                ScriptFunctionContext.EMPTY, "pi-1", "node_tech", 1);

        Object opinions = result.instanceVariables().get("nodeOpinions");
        assertThat(opinions).isInstanceOf(List.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) opinions;
        // 聚合按节点取全部意见（roundNo 过滤已移除——complete 路径记录轮次为空，多轮意见一并纳入）
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("actorId", "11").containsEntry("action", "APPROVE");
        @SuppressWarnings("unchecked") Map<String, Object> fields0 = (Map<String, Object>) rows.get(0).get("fields");
        assertThat(fields0)
                .containsEntry("riskRating", "HIGH").containsEntry("downtimeMinutes", 180);
        assertThat(rows.get(1)).containsEntry("actorId", "12");
        @SuppressWarnings("unchecked") Map<String, Object> fields1 = (Map<String, Object>) rows.get(1).get("fields");
        assertThat(fields1)
                .containsEntry("riskRating", "MEDIUM").containsEntry("downtimeMinutes", 300);
        assertThat(rows.get(2)).containsEntry("actorId", "14");
    }

    @Test
    @DisplayName("本节点无意见记录时返回原上下文（不伪造空快照）")
    void shouldReturnOriginalContextWithoutRecords() {
        when(actionServiceProvider.getIfAvailable()).thenReturn(actionService);
        when(actionService.findByProcessInstanceId("pi-1")).thenReturn(List.of(
                record("node_other", 1, 11L, "APPROVE", "{\"x\":1}")));

        ScriptFunctionContext result = service().withNodeOpinions(
                ScriptFunctionContext.EMPTY, "pi-1", "node_tech", 1);

        assertThat(result).isSameAs(ScriptFunctionContext.EMPTY);
        assertThat(result.instanceVariables()).doesNotContainKey("nodeOpinions");
    }

    @Test
    @DisplayName("意见记录服务缺席时原样返回（旧构造兼容）")
    void shouldReturnOriginalContextWithoutService() {
        TriggerExecutionService legacy = new TriggerExecutionService(null, null, null,
                null, null, null, null, objectMapper, null, null, null, null);

        ScriptFunctionContext result = legacy.withNodeOpinions(
                ScriptFunctionContext.EMPTY, "pi-1", "node_tech", 1);

        assertThat(result).isSameAs(ScriptFunctionContext.EMPTY);
        Mockito.verifyNoInteractions(actionService);
    }
}
