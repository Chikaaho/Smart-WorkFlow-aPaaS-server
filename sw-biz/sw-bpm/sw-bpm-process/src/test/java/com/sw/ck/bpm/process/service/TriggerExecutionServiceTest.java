package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTriggerExec;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TriggerExecutionService} 受控判断与可靠动作单测（ADR-P64-001 §3/§4）。
 * <p>
 * 覆盖：合法完成才触发、命中分支登记 ORCH_ACTION_START 意图、集合/分组派发、
 * 空集合/超限拒绝、脚本异常不产生动作、同身份执行幂等、驳回/退回不触发。
 * </p>
 */
@DisplayName("P64 触发器受控判断与动作派发测试")
class TriggerExecutionServiceTest {

    private NodeFormDataService nodeFormDataService;
    private BpmVariableSnapshotService variableSnapshotService;
    private BpmCommandQueue commandQueue;
    private BpmTriggerExecMapper triggerExecMapper;
    private BpmActionRefMapper actionRefMapper;
    private BpmTaskFacade bpmTaskFacade;
    private TriggerExecutionService service;

    @BeforeEach
    void setUp() {
        nodeFormDataService = mock(NodeFormDataService.class);
        variableSnapshotService = mock(BpmVariableSnapshotService.class);
        commandQueue = mock(BpmCommandQueue.class);
        triggerExecMapper = mock(BpmTriggerExecMapper.class);
        actionRefMapper = mock(BpmActionRefMapper.class);
        bpmTaskFacade = mock(BpmTaskFacade.class);
        service = new TriggerExecutionService(nodeFormDataService, variableSnapshotService,
                scriptPort(), commandQueue, triggerExecMapper, actionRefMapper, bpmTaskFacade,
                new ObjectMapper());
    }

    /**
     * 脚本端口桩（引擎侧 GraalJS 不在 process 测试类路径——隔离门禁）：
     * 按脚本内容映射可判定结果。
     */
    private com.sw.ck.bpm.api.script.BpmScriptEvaluatePort scriptPort() {
        com.sw.ck.bpm.api.script.BpmScriptEvaluatePort port =
                mock(com.sw.ck.bpm.api.script.BpmScriptEvaluatePort.class);
        when(port.run(anyString(), any(), anyLong())).thenAnswer(invocation -> {
            String script = invocation.getArgument(0);
            if (script.contains("nonexistent()")) {
                return java.util.Optional.of(
                        new com.sw.ck.bpm.api.script.BpmScriptEvaluatePort.ScriptOutcome(
                                "SCRIPT_ERROR", null, null, "引用错误: nonexistent is not defined", 5L));
            }
            if (script.contains("'X'")) {
                return java.util.Optional.of(
                        new com.sw.ck.bpm.api.script.BpmScriptEvaluatePort.ScriptOutcome(
                                "OK", "X", "STRING", null, 5L));
            }
            if (script.contains("true")) {
                return java.util.Optional.of(
                        new com.sw.ck.bpm.api.script.BpmScriptEvaluatePort.ScriptOutcome(
                                "OK", Boolean.TRUE, "BOOLEAN", null, 5L));
            }
            return java.util.Optional.of(
                    new com.sw.ck.bpm.api.script.BpmScriptEvaluatePort.ScriptOutcome(
                            "OK", 1L, "NUMBER", null, 5L));
        });
        return port;
    }

    private BpmInstance instance() {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId("pi-1");
        instance.setProcessDefKey("def_main");
        instance.setBusinessKey("rec-1");
        instance.setFormKey("main_form");
        instance.setInitiatorId(7L);
        instance.setTenantId(9L);
        instance.setDefVersion(2);
        return instance;
    }

    private BpmTaskDTO task(String nodeKey) {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("task-1");
        task.setTaskDefinitionKey(nodeKey);
        task.setProcessInstanceId("pi-1");
        task.setProcessDefinitionKey("def_main");
        return task;
    }

    private TriggerConfig trigger(String event, String nodeKey, String script,
                                  List<TriggerConfig.TriggerBranch> branches) {
        return TriggerConfig.builder()
                .triggerId("trg-1").name("t").event(event).nodeKey(nodeKey)
                .script(script).variables(List.of("v_set")).branches(branches)
                .build();
    }

    private TriggerConfig.TriggerBranch branch(String id, String type, String value, ActionConfig action) {
        return TriggerConfig.TriggerBranch.builder()
                .branchId(id).name("b").matchType(type).matchValue(value)
                .actions(action == null ? List.of() : List.of(action)).build();
    }

    private ActionConfig eachAction(int maxDispatch) {
        return ActionConfig.builder()
                .actionId("act-1").name("a").type("START_EACH")
                .sourceVariable("v_set").targetProcessDefKey("def_target")
                .targetFormKey("target_form").maxDispatch(maxDispatch)
                .mapping(List.of(ActionConfig.ActionMapping.builder()
                        .targetField("owner").itemField("id").build()))
                .build();
    }

    private void stubGraph(BpmInstance instance, TriggerConfig trigger) {
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("def_main")
                .variables(List.of(ProcessVariableDef.builder()
                        .varId("v_set").type("USER_SET").source("MAIN_FORM")
                        .sourceField("handlers").aggregation("UNION").nullable(false).build()))
                .triggers(List.of(trigger))
                .build();
        when(nodeFormDataService.loadGraph("def_main", 2)).thenReturn(graph);
        when(nodeFormDataService.currentRound("pi-1")).thenReturn(1L);
        when(variableSnapshotService.buildSnapshot(eq(9L), eq(instance), any(), any(), any(), anyLong()))
                .thenReturn(new BpmVariableSnapshotService.SnapshotResult(
                        Map.of("v_set", List.of("11", "12", "13")),
                        "{\"variables\":{\"v_set\":[\"11\",\"12\",\"13\"]}}",
                        false, List.of(), List.of()));
    }

    @Test
    @DisplayName("APPROVE 完成且分支命中：逐项登记 ORCH_ACTION_START 意图与命令")
    void shouldDispatchIntentsOnMatchedBranch() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("TASK_SUBMITTED", "node_qc", "return 1;",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of()));
        when(commandQueue.enqueue(any())).thenReturn(101L);
        when(actionRefMapper.selectOne(any())).thenReturn(null);

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        ArgumentCaptor<CommandEnvelope> envelopeCaptor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue, times(3)).enqueue(envelopeCaptor.capture());
        List<CommandEnvelope> envelopes = envelopeCaptor.getAllValues();
        assertThat(envelopes).allSatisfy(envelope -> {
            assertThat(envelope.getCommandType()).isEqualTo(CommandTypeEnum.ORCH_ACTION_START);
            assertThat(envelope.getChannel()).isEqualTo(CommandChannelEnum.NORMAL);
            assertThat(envelope.getTenantId()).isEqualTo(9L);
            assertThat(envelope.getCommandKey()).startsWith("P64ACT:TRG:pi-1:trg-1:TASK_SUBMITTED:1:task-1:act-1:");
        });
        assertThat(envelopes).extracting(CommandEnvelope::getCommandKey)
                .containsExactlyInAnyOrder(
                        "P64ACT:TRG:pi-1:trg-1:TASK_SUBMITTED:1:task-1:act-1:11",
                        "P64ACT:TRG:pi-1:trg-1:TASK_SUBMITTED:1:task-1:act-1:12",
                        "P64ACT:TRG:pi-1:trg-1:TASK_SUBMITTED:1:task-1:act-1:13");
        ArgumentCaptor<BpmActionRef> refCaptor = ArgumentCaptor.forClass(BpmActionRef.class);
        verify(actionRefMapper, times(3)).insert(refCaptor.capture());
        assertThat(refCaptor.getAllValues()).allSatisfy(ref ->
                assertThat(ref.getStatus()).isEqualTo("INTENT_SUBMITTED"));
        ArgumentCaptor<BpmTriggerExec> execCaptor = ArgumentCaptor.forClass(BpmTriggerExec.class);
        verify(triggerExecMapper).insert(execCaptor.capture());
        assertThat(execCaptor.getValue().getStatus()).isEqualTo("MATCHED");
        assertThat(execCaptor.getValue().getResultValue()).isEqualTo("1");
    }

    @Test
    @DisplayName("REJECT/RETURN 不触发；DISAPPROVE 合法完成触发")
    void shouldTriggerOnlyOnLegalCompletion() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("TASK_SUBMITTED", "node_qc", "return 1;",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.REJECT);
        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.RETURN);

        verify(triggerExecMapper, never()).insert(any(BpmTriggerExec.class));
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("未匹配/脚本返回 null：UNMATCHED 落行，不登记命令")
    void shouldRecordUnmatchedWithoutActions() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("TASK_SUBMITTED", "node_qc", "return 'X';",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of()));

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        ArgumentCaptor<BpmTriggerExec> execCaptor = ArgumentCaptor.forClass(BpmTriggerExec.class);
        verify(triggerExecMapper).insert(execCaptor.capture());
        assertThat(execCaptor.getValue().getStatus()).isEqualTo("UNMATCHED");
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("脚本异常/超限：FAILED 落行（含诊断），不登记命令")
    void shouldRecordScriptFailureWithoutActions() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("TASK_SUBMITTED", "node_qc", "return nonexistent();",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of()));

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        ArgumentCaptor<BpmTriggerExec> execCaptor = ArgumentCaptor.forClass(BpmTriggerExec.class);
        verify(triggerExecMapper).insert(execCaptor.capture());
        assertThat(execCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(execCaptor.getValue().getErrorText()).isNotBlank();
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("必填变量缺失：触发被阻止（BLOCK），无命令")
    void shouldBlockOnMissingRequiredVariables() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("TASK_SUBMITTED", "node_qc", "return 1;",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        when(variableSnapshotService.buildSnapshot(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(new BpmVariableSnapshotService.SnapshotResult(
                        Map.of(), null, false, List.of("v_set"), List.of()));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of()));

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        ArgumentCaptor<BpmTriggerExec> execCaptor = ArgumentCaptor.forClass(BpmTriggerExec.class);
        verify(triggerExecMapper).insert(execCaptor.capture());
        assertThat(execCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(execCaptor.getValue().getDisposition()).isEqualTo("BLOCK");
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("同身份执行已存在：幂等跳过不重复评估")
    void shouldSkipDuplicateExecutionByIdempotentKey() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("TASK_SUBMITTED", "node_qc", "return 1;",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        when(triggerExecMapper.selectCount(any())).thenReturn(1L);

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        verify(triggerExecMapper, never()).insert(any(BpmTriggerExec.class));
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("派发集合为空/超限：整体拒绝并落诊断，不静默截断")
    void shouldRejectEmptyAndOverLimitCollections() {
        BpmInstance instance = instance();
        when(nodeFormDataService.loadGraph("def_main", 2)).thenReturn(ProcessGraph.builder()
                .processKey("def_main")
                .variables(List.of(ProcessVariableDef.builder()
                        .varId("v_set").type("USER_SET").source("MAIN_FORM")
                        .sourceField("handlers").aggregation("UNION").nullable(false).build()))
                .triggers(List.of(trigger("TASK_SUBMITTED", "node_qc", "return 1;",
                        List.of(branch("b1", "NUMBER", "1", eachAction(2))))))
                .build());
        when(nodeFormDataService.currentRound("pi-1")).thenReturn(1L);
        when(variableSnapshotService.buildSnapshot(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(new BpmVariableSnapshotService.SnapshotResult(
                        Map.of("v_set", List.of("11", "12", "13")), "{}", false, List.of(), List.of()));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of()));

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        verify(commandQueue, never()).enqueue(any());
        ArgumentCaptor<BpmTriggerExec> execCaptor = ArgumentCaptor.forClass(BpmTriggerExec.class);
        verify(triggerExecMapper).insert(execCaptor.capture());
        verify(triggerExecMapper).updateById(execCaptor.getValue());
        assertThat(execCaptor.getValue().getErrorText()).contains("超过上限");
        assertThat(execCaptor.getValue().getErrorText()).contains("整体拒绝");

        // 空集合路径
        when(variableSnapshotService.buildSnapshot(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(new BpmVariableSnapshotService.SnapshotResult(
                        Map.of("v_set", List.of()), "{}", false, List.of(), List.of()));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);
        verify(triggerExecMapper, times(2)).insert(execCaptor.capture());
        assertThat(execCaptor.getValue().getErrorText()).contains("派发集合为空");
    }

    @Test
    @DisplayName("PROCESS_COMPLETED 仅 APPROVED 终态触发")
    void shouldTriggerProcessCompletedOnlyOnApproved() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("PROCESS_COMPLETED", null, "return true;",
                List.of(branch("b1", "BOOLEAN", "true", eachAction(50)))));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(commandQueue.enqueue(any())).thenReturn(101L);
        when(actionRefMapper.selectOne(any())).thenReturn(null);

        service.onProcessCompleted(instance, "APPROVED");
        verify(commandQueue, times(3)).enqueue(any());

        service.onProcessCompleted(instance, "REJECTED");
        service.onProcessCompleted(instance, "WITHDRAWN");
        // 仍只 3 次（REJECTED/WITHDRAWN 不触发）
        verify(commandQueue, times(3)).enqueue(any());
    }

    @Test
    @DisplayName("无触发器配置的旧图零行为（A12 存量兼容）")
    void shouldNoOpOnGraphWithoutTriggers() {
        BpmInstance instance = instance();
        when(nodeFormDataService.loadGraph("def_main", 2)).thenReturn(ProcessGraph.builder()
                .processKey("def_main").build());

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);
        service.onProcessCompleted(instance, "APPROVED");

        verify(triggerExecMapper, never()).insert(any(BpmTriggerExec.class));
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("同身份意图已存在：回查不重复入队")
    void shouldDeduplicateIntentsByCommandKey() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("TASK_SUBMITTED", "node_qc", "return 1;",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of()));
        when(actionRefMapper.selectOne(any())).thenReturn(new BpmActionRef());

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        verify(commandQueue, never()).enqueue(any());
        verify(triggerExecMapper).updateById(any(BpmTriggerExec.class));
    }

    @Test
    @DisplayName("节点仍有活跃任务时不触发 NODE_ROUND_COMPLETED")
    void shouldNotFireNodeRoundCompletedWhileTasksActive() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("NODE_ROUND_COMPLETED", "node_qc", "return 1;",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        BpmTaskDTO activeTask = task("node_qc");
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of(activeTask)));

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        verify(triggerExecMapper, never()).insert(any(BpmTriggerExec.class));
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("节点任务全部完成时触发 NODE_ROUND_COMPLETED（exec_key 不含任务身份）")
    void shouldFireNodeRoundCompletedWhenNodeQuiet() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("NODE_ROUND_COMPLETED", "node_qc", "return 1;",
                List.of(branch("b1", "NUMBER", "1", eachAction(50)))));
        when(triggerExecMapper.selectCount(any())).thenReturn(0L);
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of()));
        when(commandQueue.enqueue(any())).thenReturn(101L);
        when(actionRefMapper.selectOne(any())).thenReturn(null);

        service.onTaskActionCompleted(instance, task("node_qc"), ApprovalAction.APPROVE);

        ArgumentCaptor<CommandEnvelope> envelopeCaptor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue, times(3)).enqueue(envelopeCaptor.capture());
        assertThat(envelopeCaptor.getAllValues()).allSatisfy(envelope ->
                assertThat(envelope.getCommandKey())
                        .startsWith("P64ACT:TRG:pi-1:trg-1:NODE_ROUND_COMPLETED:1:act-1:")
                        .doesNotContain("task-1"));
    }
}
