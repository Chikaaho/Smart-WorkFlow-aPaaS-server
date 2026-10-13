package com.sw.ck.bpm.engine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.engine.delegate.ConsensusCompletionEvaluator;
import com.sw.ck.bpm.engine.delegate.DynamicBranchCollectionResolver;
import com.sw.ck.bpm.engine.listener.ApprovalTaskListener;
import com.sw.ck.bpm.engine.listener.DynamicBranchTaskListener;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.bpm.engine.resolver.DesignatedApproverResolver;
import com.sw.ck.bpm.engine.translator.ApprovalUserTaskTranslator;
import com.sw.ck.bpm.engine.translator.GraphToBpmnTranslator;
import com.sw.ck.bpm.engine.translator.ParallelGatewayTranslator;
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
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
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
 * P63 §2/A09 手工并行兼容行为证据（真实 Flowable）：
 * 并行网关多路径同时推进、多节点路径、AND 汇合只推进一次；
 * 路径内动态并行先完成自身汇聚再参与外层汇合；其他路径不受来源集合大小影响。
 */
class P63ManualParallelFlowableTest {

    private static final AtomicInteger SETTLEMENTS = new AtomicInteger();
    private static ProcessEngine processEngine;
    private static RuntimeService runtimeService;
    private static TaskService taskService;
    private static Deployment deployment;

    @BeforeAll
    static void startEngine() {
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                        .createStandaloneInMemProcessEngineConfiguration();
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJdbcUrl("jdbc:h2:mem:p63-manual-parallel;DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");

        DeptQueryFacade deptQueryFacade = mock(DeptQueryFacade.class);
        when(deptQueryFacade.findActiveDeptIds(anyCollection()))
                .thenAnswer(inv -> Optional.of(toIds(inv.getArgument(0))));
        UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
        when(userQueryFacade.findActiveUserIdsByDeptLeaders(anyCollection(), eq(1L)))
                .thenAnswer(inv -> {
                    List<Long> ids = toIds(inv.getArgument(0));
                    List<Long> result = new ArrayList<>();
                    for (Long id : ids) {
                        if (id == 1L) result.add(30L);
                        if (id == 2L) result.add(40L);
                    }
                    return Optional.of(result);
                });

        DynamicBranchPort branchPort = new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String tenantId, String processInstanceId, String nodeKey,
                                                       String sourceType, String sourceDesc, String mode,
                                                       List<BranchCandidate> candidates) {
                List<FrozenBranch> result = new ArrayList<>();
                int index = 0;
                for (BranchCandidate candidate : candidates) {
                    if (candidate.skipReason() == null && candidate.leaderId() != null) {
                        result.add(new FrozenBranch(index++, candidate.leaderId(), candidate.deptId()));
                    }
                }
                return Optional.of(result);
            }

            @Override
            public Optional<MutationOutcome> recordAction(String tenantId, String processInstanceId, String nodeKey,
                                                          String leaderId, String taskId, String action,
                                                          String reason) {
                return Optional.of(MutationOutcome.APPLIED);
            }

            @Override
            public Optional<MutationOutcome> closeRemaining(String tenantId, String processInstanceId,
                                                            String nodeKey, String reason) {
                SETTLEMENTS.incrementAndGet();
                return Optional.of(MutationOutcome.APPLIED);
            }
        };
        com.sw.ck.bpm.api.participant.ConsensusVotePort votePort =
                new com.sw.ck.bpm.api.participant.ConsensusVotePort() {
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
        com.sw.ck.bpm.api.participant.ConsensusSettlementPort settlementPort =
                (tenantId, processInstanceId, nodeKey, reason) -> Optional.of(MutationOutcome.APPLIED);
        ParticipantSnapshotRecorder snapshotRecorder = (processInstanceId, nodeKey, taskId,
                                                        participantIds, tenantId) ->
                Optional.of(MutationOutcome.APPLIED);

        processEngine = config.buildProcessEngine();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();

        DynamicBranchCollectionResolver resolver = new DynamicBranchCollectionResolver(
                processEngine.getRepositoryService(), new ObjectMapper(),
                mock(ParticipantResolverRegistry.class), deptQueryFacade, userQueryFacade,
                providerOf(branchPort),
                (tenantId, formKey, recordId) -> Optional.empty(), null);
        DynamicBranchTaskListener listener = new DynamicBranchTaskListener(runtimeService,
                providerOf(snapshotRecorder), providerOf(votePort), providerOf(branchPort));
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
    @DisplayName("手工并行：A/B 路径同时推进、B 路径多节点、AND 汇合等全部路径到齐")
    void manualParallelForkJoinWaitsAllPaths() {
        String instanceId = deployAndStart("p63_manual_parallel", graphApprovalPaths());
        try {
            List<Task> tasks = taskService.createTaskQuery().processInstanceId(instanceId).list();
            assertThat(tasks).extracting(Task::getTaskDefinitionKey)
                    .as("分叉后 A1 与 B1 同时活跃")
                    .containsExactlyInAnyOrder("a1", "b1");

            completeByNode(instanceId, "a1");
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count())
                    .as("A 路径到齐但 B 未完，AND 汇合不放行，实例不结束").isEqualTo(1L);
            assertThat(taskService.createTaskQuery().processInstanceId(instanceId).list())
                    .extracting(Task::getTaskDefinitionKey)
                    .as("B 路径仍在首节点，未受 A 完成影响").containsExactly("b1");

            completeByNode(instanceId, "b1");
            completeByNode(instanceId, "b2");
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count()).as("全部路径到齐后恰好一次结束").isZero();
        } finally {
            deleteQuietly(instanceId);
        }
    }

    @Test
    @DisplayName("路径内动态并行：先完成自身汇聚再参与外层汇合；另一路径不受来源集合影响")
    void dynamicPathConvergesBeforeOuterJoin() {
        String instanceId = deployAndStart("p63_manual_dynamic_path", graphDynamicPath());
        try {
            List<Task> tasks = taskService.createTaskQuery().processInstanceId(instanceId).list();
            assertThat(tasks).extracting(Task::getAssignee)
                    .as("A 审批与动态并行两分支任务同时活跃（分支办理人 30/40）")
                    .containsExactlyInAnyOrder("101", "30", "40");

            completeByNode(instanceId, "a1");
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count())
                    .as("A 路径到齐但动态路径未汇聚，实例不结束").isEqualTo(1L);

            // 只完成一条动态分支：动态汇聚未达成，外层汇合不放行
            completeAssignee(instanceId, "30");
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count()).isEqualTo(1L);

            // 第二条动态分支完成：动态汇聚达成 → 外层 AND 汇合放行 → 结束
            completeAssignee(instanceId, "40");
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count()).isZero();
        } finally {
            deleteQuietly(instanceId);
        }
    }

    private void completeByNode(String instanceId, String nodeKey) {
        Task task = taskService.createTaskQuery().processInstanceId(instanceId)
                .taskDefinitionKey(nodeKey).singleResult();
        taskService.complete(task.getId(), Map.of("outcome", "APPROVED"));
    }

    private void completeAssignee(String instanceId, String assignee) {
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(instanceId)
                .taskAssignee(assignee).list();
        assertThat(tasks).as("办理人 " + assignee + " 应有恰好一条待办").hasSize(1);
        taskService.complete(tasks.get(0).getId(), Map.of("outcome", "APPROVED"));
    }

    private String deployAndStart(String processKey, ProcessGraph graph) {
        // 内置 START/END/APPROVAL 由插件构造器自带；此处仅补并行网关与动态并行
        GraphToBpmnTranslator translator = new GraphToBpmnTranslator(new ObjectMapper(),
                List.of(new ParallelGatewayTranslator(),
                        new com.sw.ck.bpm.engine.translator.DynamicParallelNodeTranslator(new ObjectMapper())));
        BpmnModel model = translator.translate(graph);
        deployment = processEngine.getRepositoryService().createDeployment()
                .addBytes(processKey + ".bpmn20.xml", new BpmnXMLConverter().convertToXML(model))
                .name(processKey + "-" + System.nanoTime())
                .deploy();
        ProcessDefinition definition = processEngine.getRepositoryService()
                .createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        Map<String, Object> variables = new HashMap<>();
        variables.put("tenantId", 1L);
        variables.put("deptList", List.of(1L, 2L));
        return runtimeService.startProcessInstanceById(definition.getId(), variables).getId();
    }

    /** start → split →(A1)→ join →end；split →(B1→B2)→ join。 */
    private ProcessGraph graphApprovalPaths() {
        Map<String, Object> approver = Map.of("type", "DESIGNATED");
        return ProcessGraph.builder()
                .processKey("p63_manual_parallel")
                .elements(List.of(
                        node("start", "START"),
                        node("split", "PARALLEL_GATEWAY"),
                        node("a1", "APPROVAL", Map.of("participant",
                                Map.of("type", "DESIGNATED", "value", List.of(101)))),
                        node("b1", "APPROVAL", Map.of("participant",
                                Map.of("type", "DESIGNATED", "value", List.of(201)))),
                        node("b2", "APPROVAL", Map.of("participant",
                                Map.of("type", "DESIGNATED", "value", List.of(202)))),
                        node("join", "PARALLEL_GATEWAY"),
                        node("end", "END"),
                        edge("e1", "start", "split"),
                        edge("e2", "split", "a1"),
                        edge("e3", "split", "b1"),
                        edge("e4", "a1", "join"),
                        edge("e5", "b1", "b2"),
                        edge("e6", "b2", "join"),
                        edge("e7", "join", "end")))
                .build();
    }

    /** start → split →(A1)→ join；split →(dyn 动态并行)→ join。 */
    private ProcessGraph graphDynamicPath() {
        Map<String, Object> dynConfig = new LinkedHashMap<>();
        dynConfig.put("name", "动态并行");
        dynConfig.put("mode", "ALL");
        dynConfig.put("maxBranches", 50);
        dynConfig.put("source", Map.of("type", "VARIABLE", "value", "deptList"));
        return ProcessGraph.builder()
                .processKey("p63_manual_dynamic_path")
                .elements(List.of(
                        node("start", "START"),
                        node("split", "PARALLEL_GATEWAY"),
                        node("a1", "APPROVAL", Map.of("participant",
                                Map.of("type", "DESIGNATED", "value", List.of(101)))),
                        node("dyn", "DYNAMIC_PARALLEL", dynConfig),
                        node("join", "PARALLEL_GATEWAY"),
                        node("end", "END"),
                        edge("e1", "start", "split"),
                        edge("e2", "split", "a1"),
                        edge("e3", "split", "dyn"),
                        edge("e4", "a1", "join"),
                        edge("e5", "dyn", "join"),
                        edge("e6", "join", "end")))
                .build();
    }

    private static GraphElement node(String id, String type) {
        return GraphElement.builder().id(id).kind("node").type(type).build();
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type).config(config).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target).build();
    }

    private static List<Long> toIds(Object raw) {
        List<Long> ids = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            ids.add(Long.valueOf(String.valueOf(item)));
        }
        return ids;
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
        };
    }

    private void deleteQuietly(String instanceId) {
        try {
            runtimeService.deleteProcessInstance(instanceId, "cleanup");
        } catch (Exception ignored) {
        }
    }
}
