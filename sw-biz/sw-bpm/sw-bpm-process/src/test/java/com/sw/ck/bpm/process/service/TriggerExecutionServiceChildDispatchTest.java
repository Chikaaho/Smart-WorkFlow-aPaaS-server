package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmChildItem;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmChildBatchMapper;
import com.sw.ck.bpm.process.mapper.BpmChildItemMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅱ（A05）CHILD 动作派发登记单测：
 * 派发同事务冻结批次并逐项登记（预期数/批次身份/载荷回查链）；NONE 派发即结算；
 * 编排未装配时零行为（阶段Ⅰ回退口径）。
 */
@DisplayName("P64 CHILD 动作派发登记测试")
class TriggerExecutionServiceChildDispatchTest {

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                BpmChildBatch.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                BpmChildItem.class);
    }

    private NodeFormDataService nodeFormDataService;
    private BpmVariableSnapshotService variableSnapshotService;
    private BpmCommandQueue commandQueue;
    private BpmTriggerExecMapper triggerExecMapper;
    private BpmActionRefMapper actionRefMapper;
    private BpmTaskFacade bpmTaskFacade;
    private ChildOrchestrationService orchestration;
    private BpmChildBatchMapper batchMapper;
    private BpmChildItemMapper itemMapper;
    private TriggerExecutionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nodeFormDataService = mock(NodeFormDataService.class);
        variableSnapshotService = mock(BpmVariableSnapshotService.class);
        commandQueue = mock(BpmCommandQueue.class);
        triggerExecMapper = mock(BpmTriggerExecMapper.class);
        actionRefMapper = mock(BpmActionRefMapper.class);
        bpmTaskFacade = mock(BpmTaskFacade.class);
        orchestration = mock(ChildOrchestrationService.class);
        batchMapper = mock(BpmChildBatchMapper.class);
        itemMapper = mock(BpmChildItemMapper.class);
        ObjectProvider<ChildOrchestrationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(orchestration);
        service = new TriggerExecutionService(nodeFormDataService, variableSnapshotService,
                scriptPort(), commandQueue, triggerExecMapper, actionRefMapper, bpmTaskFacade,
                new ObjectMapper(), provider);
    }

    @Test
    @DisplayName("CHILD 派发：批次冻结一次、逐项登记、载荷携带批次身份；转办/重试不增加预期数由批次冻结保证")
    void childDispatchFreezesBatchAndRegistersItems() {
        BpmInstance instance = instance();
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graph());
        when(nodeFormDataService.currentRound("p1")).thenReturn(1L);
        when(bpmTaskFacade.queryByProcessInstance("p1")).thenReturn(Optional.of(List.of()));
        when(variableSnapshotService.buildSnapshot(eq(9L), any(), any(), any(), any(), anyLong()))
                .thenReturn(snapshot(Map.of("var_rows", List.of(
                        Map.of("id", "row-1", "owner", "2", "feedback", ""),
                        Map.of("id", "row-2", "owner", "3", "feedback", "")))));

        BpmChildBatch batch = new BpmChildBatch();
        batch.setTenantId(9L);
        batch.setId(77L);
        batch.setBatchKey("CHILD:TRG:p1:trg:1:1:act_child");
        batch.setWaitPolicy("ALL");
        when(orchestration.freezeBatch(any(), anyString(), any(), anyString(), eq(2), anyLong(),
                any(), eq(9L))).thenReturn(batch);
        when(orchestration.loadBatch(eq(9L), eq(77L))).thenReturn(batch);

        service.onTaskActionCompleted(instance, task("node_1"), ApprovalAction.APPROVE);

        // 批次冻结一次、两项登记（各自冻结本项来源行集合）、意图登记两条
        verify(orchestration).freezeBatch(any(), eq("trg"), any(), anyString(), eq(2),
                eq(1L), eq("parent_form"), eq(9L));
        verify(orchestration, times(2)).registerItem(eq(batch), any(), anyString(), anyList(),
                anyString(), anyString(), anyString(), eq("parent_form"), eq(9L));
        ArgumentCaptor<BpmActionRef> refCaptor = ArgumentCaptor.forClass(BpmActionRef.class);
        verify(actionRefMapper, times(2)).insert(refCaptor.capture());
        assertThat(refCaptor.getAllValues())
                .allSatisfy(ref -> assertThat(ref.getPayloadJson()).contains("childBatchId"));
    }

    @Test
    @DisplayName("NONE 策略：批次登记完成后立即结算（派发意图提交即继续）")
    void nonePolicySettlesImmediatelyAfterItemsRegistered() {
        BpmInstance instance = instance();
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graph());
        when(nodeFormDataService.currentRound("p1")).thenReturn(1L);
        when(bpmTaskFacade.queryByProcessInstance("p1")).thenReturn(Optional.of(List.of()));
        when(variableSnapshotService.buildSnapshot(eq(9L), any(), any(), any(), any(), anyLong()))
                .thenReturn(snapshot(Map.of("var_rows", List.of(
                        Map.of("id", "row-1", "owner", "2")))));

        BpmChildBatch batch = new BpmChildBatch();
        batch.setTenantId(9L);
        batch.setId(88L);
        batch.setBatchKey("CHILD:x");
        batch.setWaitPolicy("NONE");
        when(orchestration.freezeBatch(any(), anyString(), any(), anyString(), eq(1), anyLong(),
                any(), eq(9L))).thenReturn(batch);
        when(orchestration.loadBatch(eq(9L), eq(88L))).thenReturn(batch);

        service.onTaskActionCompleted(instance, task("node_1"), ApprovalAction.APPROVE);

        verify(orchestration).settleIfNone(batch);
    }

    @Test
    @DisplayName("CHILD 分组行级回写：登记冻结本组来源行集合，子记录表格预填本项授权行（仅本实例的行）")
    void childDispatchPrefillsAuthorizedSourceRowsIntoChildRecord() throws Exception {
        BpmInstance instance = instance();
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graphWithRowWriteBack());
        when(nodeFormDataService.currentRound("p1")).thenReturn(1L);
        when(bpmTaskFacade.queryByProcessInstance("p1")).thenReturn(Optional.of(List.of()));
        when(variableSnapshotService.buildSnapshot(eq(9L), any(), any(), any(), any(), anyLong()))
                .thenReturn(snapshot(Map.of("var_rows", List.of(
                        Map.of("id", "row-1", "owner", "2", "feedback", ""),
                        Map.of("id", "row-2", "owner", "2", "feedback", ""),
                        Map.of("id", "row-3", "owner", "3", "feedback", "")))));

        BpmChildBatch batch = new BpmChildBatch();
        batch.setTenantId(9L);
        batch.setId(99L);
        batch.setBatchKey("CHILD:y");
        batch.setWaitPolicy("ALL");
        when(orchestration.freezeBatch(any(), anyString(), any(), anyString(), eq(2), anyLong(),
                any(), eq(9L))).thenReturn(batch);
        when(orchestration.loadBatch(eq(9L), eq(99L))).thenReturn(batch);

        service.onTaskActionCompleted(instance, task("node_1"), ApprovalAction.APPROVE);

        // 分组项 2：owner=2 组含 row-1/row-2（多行），owner=3 组含 row-3
        ArgumentCaptor<List<String>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orchestration, times(2)).registerItem(eq(batch), any(), anyString(),
                rowsCaptor.capture(), anyString(), anyString(), anyString(), eq("parent_form"), eq(9L));
        assertThat(rowsCaptor.getAllValues())
                .anySatisfy(rows -> assertThat(rows).containsExactly("row-1", "row-2"))
                .anySatisfy(rows -> assertThat(rows).containsExactly("row-3"));

        // 子记录预填：结果表格仅含本项授权来源行（组外行不进入该子记录）
        ArgumentCaptor<BpmActionRef> refCaptor = ArgumentCaptor.forClass(BpmActionRef.class);
        verify(actionRefMapper, times(2)).insert(refCaptor.capture());
        ObjectMapper mapper = new ObjectMapper();
        List<List<String>> prefilledRowIds = new java.util.ArrayList<>();
        for (BpmActionRef ref : refCaptor.getAllValues()) {
            Map<String, Object> payload = mapper.readValue(ref.getPayloadJson(), Map.class);
            Map<String, Object> data = (Map<String, Object>) payload.get("data");
            List<Map<String, Object>> table = (List<Map<String, Object>>) data.get("result_table");
            prefilledRowIds.add(table.stream().map(row -> String.valueOf(row.get("src_row_id"))).toList());
        }
        assertThat(prefilledRowIds)
                .anySatisfy(ids -> assertThat(ids).containsExactly("row-1", "row-2"))
                .anySatisfy(ids -> assertThat(ids).containsExactly("row-3"));
    }

    @Test
    @DisplayName("派发上限护栏：集合规模超过 maxDispatch → 整体拒绝（OVER_LIMIT），不冻结批次、不登记意图（越界不新增可靠意图/实例）")
    void childDispatchOverCapRejectedWithoutFreezeOrIntents() {
        BpmInstance instance = instance();
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graph());
        when(nodeFormDataService.currentRound("p1")).thenReturn(1L);
        when(bpmTaskFacade.queryByProcessInstance("p1")).thenReturn(Optional.of(List.of()));
        when(variableSnapshotService.buildSnapshot(eq(9L), any(), any(), any(), any(), anyLong()))
                .thenReturn(snapshot(Map.of("var_rows", List.of(
                        Map.of("id", "row-1", "owner", "2", "feedback", ""),
                        Map.of("id", "row-2", "owner", "2", "feedback", ""),
                        Map.of("id", "row-3", "owner", "3", "feedback", "")))));

        ActionConfig capped = childAction();
        capped.setMaxDispatch(2);
        ProcessGraph graph = graph();
        graph.getTriggers().get(0).getBranches().get(0)
                .setActions(List.of(capped));
        when(nodeFormDataService.loadGraph("parent_def", 1)).thenReturn(graph);

        service.onTaskActionCompleted(instance, task("node_1"), ApprovalAction.APPROVE);

        // 越界：冻结从未发生、意图零新增，拒绝原因可诊断
        verify(orchestration, never()).freezeBatch(any(), anyString(), any(), anyString(),
                anyInt(), anyLong(), any(), anyLong());
        verify(actionRefMapper, never()).insert(any(BpmActionRef.class));
        ArgumentCaptor<com.sw.ck.bpm.process.entity.BpmTriggerExec> execCaptor =
                ArgumentCaptor.forClass(com.sw.ck.bpm.process.entity.BpmTriggerExec.class);
        verify(triggerExecMapper, atLeastOnce()).updateById(execCaptor.capture());
        java.util.List<String> notes = execCaptor.getAllValues().stream()
                .map(com.sw.ck.bpm.process.entity.BpmTriggerExec::getErrorText)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(notes).anySatisfy(text -> {
            assertThat(text).contains("超过上限");
            assertThat(text).contains("OVER_LIMIT");
        });
    }

    /** 分组（按 owner）+ 行级回写配置的父图（子记录表格预填断言用）。 */
    private ProcessGraph graphWithRowWriteBack() {
        ProcessGraph graph = new ProcessGraph();
        graph.setFormKey("parent_form");
        TriggerConfig trigger = TriggerConfig.builder()
                .triggerId("trg")
                .event("NODE_ROUND_COMPLETED")
                .nodeKey("node_1")
                .script("return true;")
                .branches(List.of(TriggerConfig.TriggerBranch.builder()
                        .branchId("b1")
                        .matchType("BOOLEAN")
                        .matchValue("true")
                        .actions(List.of(groupedChildAction()))
                        .build()))
                .build();
        graph.setTriggers(List.of(trigger));
        return graph;
    }

    private ActionConfig groupedChildAction() {
        return ActionConfig.builder()
                .actionId("act_child")
                .type("START_GROUPED")
                .orchestration("CHILD")
                .waitPolicy("ALL")
                .sourceVariable("var_rows")
                .groupBy("owner")
                .targetProcessDefKey("child_def")
                .targetFormKey("child_form")
                .writeBack(ActionConfig.WriteBackConfig.builder()
                        .resultNodeKey("node_result")
                        .tableField("result_table")
                        .rowKeyField("src_row_id")
                        .parentTableField("hosts")
                        .fields(List.of(ActionConfig.FieldMapping.builder()
                                .fromField("feedback").toField("feedback").build()))
                        .build())
                .build();
    }

    private BpmInstance instance() {
        BpmInstance instance = new BpmInstance();
        instance.setTenantId(9L);
        instance.setProcessInstanceId("p1");
        instance.setProcessDefKey("parent_def");
        instance.setBusinessKey("rec-1");
        instance.setFormKey("parent_form");
        instance.setDefVersion(1);
        instance.setInitiatorId(7L);
        return instance;
    }

    private BpmTaskDTO task(String nodeKey) {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("task-1");
        task.setTaskDefinitionKey(nodeKey);
        return task;
    }

    private ProcessGraph graph() {
        ProcessGraph graph = new ProcessGraph();
        graph.setFormKey("parent_form");
        TriggerConfig trigger = TriggerConfig.builder()
                .triggerId("trg")
                .event("NODE_ROUND_COMPLETED")
                .nodeKey("node_1")
                .script("return true;")
                .branches(List.of(TriggerConfig.TriggerBranch.builder()
                        .branchId("b1")
                        .matchType("BOOLEAN")
                        .matchValue("true")
                        .actions(List.of(childAction()))
                        .build()))
                .build();
        graph.setTriggers(List.of(trigger));
        return graph;
    }

    private ActionConfig childAction() {
        return ActionConfig.builder()
                .actionId("act_child")
                .type("START_EACH")
                .orchestration("CHILD")
                .waitPolicy("ALL")
                .sourceVariable("var_rows")
                .targetProcessDefKey("child_def")
                .targetFormKey("child_form")
                .build();
    }

    private BpmVariableSnapshotService.SnapshotResult snapshot(Map<String, Object> values) {
        return new BpmVariableSnapshotService.SnapshotResult(values, "{}", false, List.of(), List.of());
    }

    private BpmScriptEvaluatePort scriptPort() {
        BpmScriptEvaluatePort port = mock(BpmScriptEvaluatePort.class);
        when(port.run(anyString(), any(), anyLong())).thenAnswer(invocation ->
                Optional.of(new BpmScriptEvaluatePort.ScriptOutcome(
                        BpmScriptEvaluatePort.ScriptOutcome.KIND_OK, Boolean.TRUE, "BOOLEAN", null, 1L)));
        return port;
    }
}
