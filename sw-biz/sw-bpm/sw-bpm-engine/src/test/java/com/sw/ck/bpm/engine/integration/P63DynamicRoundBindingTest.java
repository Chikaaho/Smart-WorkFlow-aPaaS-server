package com.sw.ck.bpm.engine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.participant.ConsensusSettlementPort;
import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.engine.delegate.DynamicBranchCollectionResolver;
import com.sw.ck.bpm.engine.listener.ApprovalTaskListener;
import com.sw.ck.bpm.engine.listener.DynamicBranchTaskListener;
import com.sw.ck.bpm.engine.delegate.ConsensusCompletionEvaluator;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.bpm.engine.resolver.DesignatedApproverResolver;
import com.sw.ck.bpm.engine.translator.DynamicParallelNodeTranslator;
import com.sw.ck.bpm.engine.translator.GraphToBpmnTranslator;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.ProcessEngines;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P63 G04 业务轮次绑定证据（真实 Flowable 引擎输出，非 port 单测）：
 * 首次进入动态并行节点只产生一个业务轮次——根执行冻结一次，子实例生成的
 * 集合解析重放复用同轮（不开新轮、不关旧轮）；引擎合法重入才开新轮，
 * 旧轮与全部历史保留、逐行 SUPERSEDED_BY_ROUND 收口。
 */
class P63DynamicRoundBindingTest {

    private static ProcessEngine processEngine;
    /** 快照记录实际值输出（G04a participant snapshot）。 */
    private static List<String> snapshotRecorderRef;
    private static RuntimeService runtimeService;
    private static TaskService taskService;
    private static Deployment deployment;
    private static RecordingRoundPort roundPort;

    /** 记录型轮次台账：仿真实端口幂等口径（同 executionId 复用 / 新轮=max+1 / 旧 START 收口）。 */
    static final class RecordingRoundPort implements DynamicBranchPort {
        static final class Row {
            final long round;
            final String executionId;
            final String objectId;
            final String assigneeId;
            String status;
            String cancelReason;

            Row(long round, String executionId, String objectId, String assigneeId, String status) {
                this.round = round;
                this.executionId = executionId;
                this.objectId = objectId;
                this.assigneeId = assigneeId;
                this.status = status;
            }
        }

        final List<Row> rows = new ArrayList<>();
        final AtomicInteger newRounds = new AtomicInteger();
        final AtomicInteger reuseCalls = new AtomicInteger();
        final AtomicInteger superseded = new AtomicInteger();

        public Optional<List<FrozenBranchV2>> freezeRound(String tenantId, String processInstanceId,
                                                          String nodeKey, String executionId,
                                                          String sourceType, String sourceDesc,
                                                          String mode, String objectType,
                                                          List<BranchCandidateV2> candidates) {
            List<Row> same = rows.stream().filter(r ->
                    r.executionId.equals(executionId) && "START".equals(r.status)).toList();
            if (!same.isEmpty()) {
                // 同轮重放：幂等复用，不开新轮、不关旧轮
                reuseCalls.incrementAndGet();
                List<FrozenBranchV2> reused = new ArrayList<>();
                int index = 0;
                for (Row row : same) {
                    reused.add(new FrozenBranchV2(index, row.assigneeId, objectType,
                            row.objectId, null));
                    index++;
                }
                return Optional.of(reused);
            }
            long nextRound = rows.stream().mapToLong(r -> r.round).max().orElse(-1L) + 1;
            for (Row row : rows) {
                if ("START".equals(row.status)) {
                    row.status = "CANCELED";
                    row.cancelReason = "SUPERSEDED_BY_ROUND";
                    superseded.incrementAndGet();
                }
            }
            newRounds.incrementAndGet();
            List<FrozenBranchV2> result = new ArrayList<>();
            int index = 0;
            for (BranchCandidateV2 candidate : candidates == null
                    ? List.<BranchCandidateV2>of() : candidates) {
                if (candidate.skipReason() != null || candidate.assigneeId() == null) {
                    rows.add(new Row(nextRound, executionId, candidate.objectId(), null, "CANCELED"));
                } else {
                    rows.add(new Row(nextRound, executionId, candidate.objectId(),
                            candidate.assigneeId(), "START"));
                    result.add(new FrozenBranchV2(index, candidate.assigneeId(), objectType,
                            candidate.objectId(), candidate.sourceRefsJson()));
                    index++;
                }
            }
            return Optional.of(result);
        }

        @Override
        public Optional<List<FrozenBranch>> freeze(String tenantId, String processInstanceId, String nodeKey,
                                                   String sourceType, String sourceDesc, String mode,
                                                   List<BranchCandidate> candidates) {
            return Optional.empty(); // 本测试只走 v2 轮次口径
        }

        @Override
        public Optional<MutationOutcome> recordAction(String tenantId, String processInstanceId, String nodeKey,
                                                      String leaderId, String taskId, String action, String reason) {
            return Optional.of(MutationOutcome.APPLIED);
        }

        @Override
        public Optional<MutationOutcome> closeRemaining(String tenantId, String processInstanceId,
                                                        String nodeKey, String reason) {
            return Optional.of(MutationOutcome.APPLIED);
        }
    }

    @BeforeAll
    static void startEngine() {
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                        .createStandaloneInMemProcessEngineConfiguration();
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJdbcUrl("jdbc:h2:mem:p63-round-binding;DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");

        DeptQueryFacade deptQueryFacade = mock(DeptQueryFacade.class);
        when(deptQueryFacade.findActiveDeptIds(anyCollection())).thenAnswer(inv ->
                Optional.of(toIds(inv.getArgument(0))));
        UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
        when(userQueryFacade.findDeptLeaderMap(anyCollection(), eq(1L))).thenAnswer(inv -> {
            List<Long> ids = toIds(inv.getArgument(0));
            Map<Long, Long> map = new LinkedHashMap<>();
            for (Long id : ids) {
                if (id == 7L) map.put(7L, 30L);
                if (id == 8L) map.put(8L, 40L);
            }
            return Optional.of(map);
        });

        roundPort = new RecordingRoundPort();
        List<String> snapshotLog = new ArrayList<>();
        snapshotRecorderRef = snapshotLog;
        ConsensusVotePort votePort = new ConsensusVotePort() {
            @Override
            public Optional<Boolean> record(String tenantId, String processInstanceId, String nodeKey,
                                            String taskId, String actorId, String outcome) {
                return Optional.of(Boolean.TRUE);
            }

            @Override
            public Optional<Long> count(String tenantId, String processInstanceId, String nodeKey,
                                        String outcome) {
                return Optional.of(0L);
            }
        };
        ConsensusSettlementPort settlementPort =
                (tenantId, processInstanceId, nodeKey, reason) -> Optional.of(MutationOutcome.APPLIED);
        ParticipantSnapshotRecorder snapshotRecorder = (processInstanceId, nodeKey, taskId,
                                                        participantIds, tenantId) -> {
            snapshotRecorderRef.add("snapshot{instance=" + processInstanceId + ", node=" + nodeKey
                    + ", task=" + taskId + ", participants=" + participantIds + "}");
            return Optional.of(MutationOutcome.APPLIED);
        };

        processEngine = config.buildProcessEngine();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();

        DynamicBranchCollectionResolver resolver = new DynamicBranchCollectionResolver(
                processEngine.getRepositoryService(), new ObjectMapper(),
                mock(ParticipantResolverRegistry.class), deptQueryFacade, userQueryFacade,
                providerOf(roundPort),
                (tenantId, formKey, recordId) -> Optional.empty(), null);
        DynamicBranchTaskListener listener = new DynamicBranchTaskListener(runtimeService,
                providerOf(snapshotRecorder), providerOf(votePort), providerOf(roundPort));
        ApprovalTaskListener approvalListener = new ApprovalTaskListener(
                processEngine.getRepositoryService(),
                Map.of("DESIGNATED", new DesignatedApproverResolver()), new ObjectMapper());

        Map<Object, Object> beans = new HashMap<>();
        beans.put("dynamicBranchCollectionResolver", resolver);
        beans.put("dynamicBranchTaskListener", listener);
        beans.put("approvalTaskListener", approvalListener);
        beans.put("consensusCompletionEvaluator",
                new ConsensusCompletionEvaluator(providerOf(votePort), providerOf(settlementPort)));
        org.flowable.common.engine.impl.el.DefaultExpressionManager dem =
                (org.flowable.common.engine.impl.el.DefaultExpressionManager)
                        ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
                                .getExpressionManager();
        dem.setBeans(beans);
    }

    @AfterAll
    static void stopEngine() {
        if (deployment != null && processEngine != null) {
            processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
        }
        if (processEngine != null) {
            processEngine.close();
            ProcessEngines.destroy();
        }
    }

    @Test
    @DisplayName("G04：首次进入只产生一个业务轮次——根执行冻结一次，子实例重放复用，无收口")
    void firstEntryCreatesExactlyOneBusinessRound() {
        resetLedger();

        String instanceId = deployAndStart();
        try {
            List<Task> tasks = taskService.createTaskQuery().processInstanceId(instanceId).list();
            assertThat(tasks).extracting(Task::getAssignee)
                    .as("首入按去重后对象建立分支任务（30/40 各一）")
                    .containsExactlyInAnyOrder("30", "40");

            assertThat(roundPort.newRounds.get())
                    .as("首次进入只新建一个业务轮次（round0）").isEqualTo(1);
            assertThat(roundPort.superseded.get())
                    .as("首入过程中不得关闭任何旧轮").isZero();
            assertThat(roundPort.reuseCalls.get())
                    .as("子实例生成的集合解析重放须复用同轮").isGreaterThanOrEqualTo(1);
            assertThat(roundPort.rows)
                    .as("台账只含 round0 的两个分支行")
                    .allSatisfy(row -> assertThat(row.round).isZero());

            System.out.println("[P63-EV] g04a.first-entry instance=" + instanceId
                    + " source=FIXED/DEPT/7,8 mode=ALL newRounds=" + roundPort.newRounds.get()
                    + " reuseReplays=" + roundPort.reuseCalls.get() + " superseded=" + roundPort.superseded.get()
                    + " ledger=" + roundPort.rows.stream()
                            .map(r -> "{round=" + r.round + ",exec=" + r.executionId + ",objectId=" + r.objectId
                                    + ",assignee=" + r.assigneeId + ",status=" + r.status + "}")
                            .toList()
                    + " tasks=" + tasks.stream().map(t -> "{id=" + t.getId() + ",assignee=" + t.getAssignee() + "}")
                            .toList()
                    + " snapshots=" + new ArrayList<>(snapshotRecorderRef));

            complete(instanceId, "30");
            complete(instanceId, "40");
            // 两条分支全部通过 → 动态汇聚 → 推进到 a1 审批；完成 a1 才结束实例
            Task a1 = taskService.createTaskQuery().processInstanceId(instanceId)
                    .taskDefinitionKey("a1").singleResult();
            assertThat(a1).as("汇聚后推进到 a1 审批").isNotNull();
            taskService.complete(a1.getId(), Map.of("outcome", "APPROVED"));
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count())
                    .as("分支与审批全部完成后恰好一次结束").isZero();
            assertThat(roundPort.newRounds.get())
                    .as("办理全程不再开新轮").isEqualTo(1);
            System.out.println("[P63-EV] g04a.first-entry-after-complete instance=" + instanceId
                    + " newRounds=" + roundPort.newRounds.get() + " ended=true"
                    + " finalLedger=" + roundPort.rows.stream()
                            .map(r -> "{round=" + r.round + ",objectId=" + r.objectId
                                    + ",status=" + r.status + "}")
                            .toList());
        } finally {
            deleteQuietly(instanceId);
        }
    }

    @Test
    @DisplayName("G04：引擎合法重入开新轮，旧轮逐行 SUPERSEDED_BY_ROUND 收口且历史保留")
    void reentryOpensNewRoundAndKeepsHistory() {
        resetLedger();

        String instanceId = deployAndStart();
        try {
            assertThat(taskService.createTaskQuery().processInstanceId(instanceId).list())
                    .extracting(Task::getAssignee).containsExactlyInAnyOrder("30", "40");

            // 走完首入轮：两条分支通过 → 实例推进到 a1；再从 a1 合法回到 dyn（引擎状态迁移通道）
            complete(instanceId, "30");
            complete(instanceId, "40");
            assertThat(taskService.createTaskQuery().processInstanceId(instanceId)
                    .taskDefinitionKey("a1").count()).as("首轮全部通过后推进到 a1").isEqualTo(1);

            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(instanceId)
                    .moveActivityIdTo("a1", "dyn")
                    .changeState();

            assertThat(roundPort.newRounds.get()).as("重入必须开新业务轮次").isEqualTo(2);
            assertThat(roundPort.rows.stream().mapToLong(r -> r.round).distinct().count())
                    .as("新旧两轮并存，历史不覆盖").isEqualTo(2);
            assertThat(roundPort.superseded.get())
                    .as("重入时旧轮开放分支逐行 SUPERSEDED_BY_ROUND")
                    .isEqualTo(2);
            assertThat(taskService.createTaskQuery().processInstanceId(instanceId).list())
                    .as("新轮按对象重新建立任务").extracting(Task::getAssignee)
                    .containsExactlyInAnyOrder("30", "40");
            List<Task> reentryTasks = taskService.createTaskQuery()
                    .processInstanceId(instanceId).list();
            System.out.println("[P63-EV] g04a.legal-reentry instance=" + instanceId
                    + " newRounds=" + roundPort.newRounds.get()
                    + " distinctRounds=" + roundPort.rows.stream().mapToLong(r -> r.round).distinct().boxed().toList()
                    + " superseded=" + roundPort.superseded.get()
                    + " ledger=" + roundPort.rows.stream()
                            .map(r -> "{round=" + r.round + ",objectId=" + r.objectId + ",assignee=" + r.assigneeId
                                    + ",status=" + r.status + ",cancelReason=" + r.cancelReason + "}")
                            .toList()
                    + " newRoundTasks=" + reentryTasks.stream()
                            .map(t -> "{id=" + t.getId() + ",assignee=" + t.getAssignee() + "}")
                            .toList()
                    + " historyPreserved=true");
        } finally {
            deleteQuietly(instanceId);
        }
    }

    // ==================== 工具 ====================

    private static void resetLedger() {
        roundPort.rows.clear();
        roundPort.newRounds.set(0);
        roundPort.reuseCalls.set(0);
        roundPort.superseded.set(0);
    }

    private String deployAndStart() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "动态并行");
        config.put("semanticVersion", 2);
        config.put("mode", "ALL");
        config.put("source", Map.of("type", "FIXED", "objectType", "DEPT", "value", "7,8"));
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("p63_round_binding")
                .elements(List.of(
                        node("start", "START", null),
                        node("dyn", "DYNAMIC_PARALLEL", config),
                        node("a1", "APPROVAL", Map.of("participant",
                                Map.of("type", "DESIGNATED", "value", List.of(101)))),
                        node("end", "END", null),
                        edge("e1", "start", "dyn"),
                        edge("e2", "dyn", "a1"),
                        edge("e3", "a1", "end")))
                .build();
        GraphToBpmnTranslator translator = new GraphToBpmnTranslator(new ObjectMapper(),
                List.of(new DynamicParallelNodeTranslator(new ObjectMapper())));
        BpmnModel model = translator.translate(graph);
        if (deployment == null) {
            deployment = processEngine.getRepositoryService().createDeployment()
                    .addBytes("p63_round_binding.bpmn20.xml",
                            new BpmnXMLConverter().convertToXML(model))
                    .name("p63-round-binding-" + System.nanoTime())
                    .deploy();
        }
        ProcessDefinition definition = processEngine.getRepositoryService()
                .createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        Map<String, Object> variables = new HashMap<>();
        variables.put("tenantId", 1L);
        ProcessInstance instance = runtimeService
                .startProcessInstanceById(definition.getId(), variables);
        return instance.getId();
    }

    private static void complete(String instanceId, String assignee) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(instanceId).taskAssignee(assignee).list();
        assertThat(tasks).as("办理人 " + assignee + " 应有恰好一条待办").hasSize(1);
        taskService.complete(tasks.get(0).getId(), Map.of("outcome", "APPROVED"));
    }

    private static void deleteQuietly(String instanceId) {
        try {
            runtimeService.deleteProcessInstance(instanceId, "test-cleanup");
        } catch (Exception ignored) {
            // 已结束实例无任务可删
        }
    }

    private static List<Long> toIds(Object argument) {
        return new ArrayList<>((Collection<Long>) argument);
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
        };
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type).config(config).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target).build();
    }
}
