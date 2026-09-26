package com.sw.ck.bpm.engine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.ConsensusSettlementPort;
import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.engine.delegate.ConsensusCompletionEvaluator;
import com.sw.ck.bpm.engine.delegate.DynamicBranchCollectionResolver;
import com.sw.ck.bpm.engine.listener.DynamicBranchTaskListener;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.bpm.engine.translator.DynamicParallelNodeTranslator;
import com.sw.ck.bpm.engine.translator.GraphToBpmnTranslator;
import com.sw.ck.common.exception.BaseException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I4 §3.1 动态并行编排隔离行为证据：真实 Flowable 发布/发起、进入节点冻结来源对象、
 * 部门→负责人解析、同负责人去重、ALL/VETO 汇聚、单次结算，以及
 * 空集合/负责人缺失/超上限的确定结果（BLOCK 阻断 / 显式受控策略放行）。
 */
class I4DynamicParallelFlowableTest {

    /** 冻结调用记录：instance:node → 候选摘要（deptId@leader 或 deptId!reason）。 */
    private static final Map<String, List<String>> FREEZE_CALLS = new LinkedHashMap<>();
    /** 分支动作记录：taskId → action:leaderId（closed:nodeKey → 原因）。 */
    private static final Map<String, String> BRANCH_ACTIONS = new LinkedHashMap<>();
    private static final Map<String, String> VOTES = new LinkedHashMap<>();
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
        config.setJdbcUrl("jdbc:h2:mem:i4-dynamic-parallel;DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");

        DeptQueryFacade deptQueryFacade = mock(DeptQueryFacade.class);
        UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
        // 部门 99 = 失效/跨租户对象（findActiveDeptIds 永不返回）；部门 7 = 有效但负责人缺失
        when(deptQueryFacade.findActiveDeptIds(anyCollection())).thenAnswer(inv -> {
            List<Long> ids = toIds(inv.getArgument(0));
            return Optional.of(ids.stream().filter(id -> id != 99L).toList());
        });
        when(userQueryFacade.findActiveUserIdsByDeptLeaders(anyCollection(), eq(1L)))
                .thenAnswer(inv -> {
                    List<Long> ids = toIds(inv.getArgument(0));
                    List<Long> result = new ArrayList<>();
                    for (Long id : ids) {
                        switch (id.intValue()) {
                            case 1 -> result.add(10L);
                            case 2 -> result.add(20L);
                            case 3, 4 -> result.add(30L); // 同一负责人：用于去重断言
                            default -> { }
                        }
                    }
                    return Optional.of(result);
                });

        DynamicBranchPort branchPort = new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String tenantId, String processInstanceId, String nodeKey,
                                                       String sourceType, String sourceDesc, String mode,
                                                       List<BranchCandidate> candidates) {
                FREEZE_CALLS.put(processInstanceId + ":" + nodeKey,
                        candidates.stream().map(c -> c.deptId()
                                + (c.skipReason() == null ? "@" + c.leaderId() : "!" + c.skipReason()))
                                .toList());
                List<FrozenBranch> result = new ArrayList<>();
                int index = 0;
                Map<String, List<String>> byLeader = new LinkedHashMap<>();
                for (BranchCandidate candidate : candidates) {
                    if (candidate.skipReason() == null && candidate.leaderId() != null) {
                        byLeader.computeIfAbsent(candidate.leaderId(), key -> new ArrayList<>())
                                .add(candidate.deptId());
                    }
                }
                for (Map.Entry<String, List<String>> entry : byLeader.entrySet()) {
                    result.add(new FrozenBranch(index++, entry.getKey(),
                            String.join(",", entry.getValue())));
                }
                return Optional.of(result);
            }

            @Override
            public Optional<MutationOutcome> recordAction(String tenantId, String processInstanceId, String nodeKey,
                                                          String leaderId, String taskId, String action,
                                                          String reason) {
                BRANCH_ACTIONS.put(taskId, action + ":" + leaderId);
                return Optional.of(MutationOutcome.APPLIED);
            }

            @Override
            public Optional<MutationOutcome> closeRemaining(String tenantId, String processInstanceId,
                                                            String nodeKey, String reason) {
                BRANCH_ACTIONS.put("closed", reason);
                return Optional.of(MutationOutcome.APPLIED);
            }
        };

        ConsensusVotePort votePort = new ConsensusVotePort() {
            @Override
            public Optional<Boolean> record(String tenantId, String processInstanceId, String nodeKey,
                                            String taskId, String actorId, String outcome) {
                return Optional.of(VOTES.put(processInstanceId + ":" + taskId + ":" + actorId, outcome) == null);
            }

            @Override
            public Optional<Long> count(String tenantId, String processInstanceId, String nodeKey,
                                        String outcome) {
                return Optional.of(VOTES.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(processInstanceId + ":"))
                        .filter(entry -> entry.getValue().equals(outcome))
                        .count());
            }
        };
        ConsensusSettlementPort settlementPort =
                (tenantId, processInstanceId, nodeKey, reason) -> {
                    SETTLEMENTS.incrementAndGet();
                    return Optional.of(MutationOutcome.APPLIED);
                };
        ParticipantSnapshotRecorder snapshotRecorder = new ParticipantSnapshotRecorder() {
            @Override public Optional<MutationOutcome> record(String processInstanceId, String nodeKey,
                                                              String taskId, List<String> participantIds,
                                                              Long tenantId) {
                return Optional.of(MutationOutcome.APPLIED);
            }
        };

        processEngine = config.buildProcessEngine();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();

        DynamicBranchCollectionResolver resolver = new DynamicBranchCollectionResolver(
                processEngine.getRepositoryService(), new ObjectMapper(),
                mock(ParticipantResolverRegistry.class), deptQueryFacade, userQueryFacade,
                providerOf(branchPort));
        DynamicBranchTaskListener listener = new DynamicBranchTaskListener(runtimeService,
                providerOf(snapshotRecorder), providerOf(votePort), providerOf(branchPort));

        Map<Object, Object> beans = new HashMap<>();
        beans.put("dynamicBranchCollectionResolver", resolver);
        beans.put("dynamicBranchTaskListener", listener);
        beans.put("consensusCompletionEvaluator",
                new ConsensusCompletionEvaluator(providerOf(votePort), providerOf(settlementPort)));
        // 引擎已构建：直接向表达式管理器注册受控 bean（delegateExpression 解析出口）
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
    void shouldFreezeBranchesDedupeSameLeaderAndConvergeWithAll() {
        SETTLEMENTS.set(0);
        // 来源：部门 1→负责人10、2→20、3/4→30（同负责人）→ 冻结后 3 条分支（去重 3、4）
        String instanceId = deployAndStart("ALL", "BLOCK", "BLOCK",
                Map.of("deptList", List.of(1L, 2L, 3L, 4L)));
        try {
            List<String> candidates = FREEZE_CALLS.get(instanceId + ":dyn");
            assertThat(candidates).containsExactly("1@10", "2@20", "3@30", "4@30");

            List<Task> tasks = taskService.createTaskQuery().processInstanceId(instanceId).list();
            List<String> assignees = tasks.stream().map(Task::getAssignee).sorted().toList();
            assertThat(assignees).as("同负责人 3/4 去重为一条分支").containsExactly("10", "20", "30");

            tasks.forEach(task -> taskService.complete(task.getId(),
                    Map.of("outcome", "APPROVED")));
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count()).isZero();
            assertThat(SETTLEMENTS.get()).as("正向汇聚不触发负向结算").isZero();
            // 每条分支一票（幂等端口下无重复计票，按实例隔离统计）
            assertThat(VOTES.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(instanceId + ":"))
                    .filter(entry -> entry.getValue().equals("APPROVE")).count()).isEqualTo(3);
        } finally {
            deleteQuietly(instanceId);
        }
    }

    @Test
    void shouldSettleExactlyOnceOnVeto() {
        SETTLEMENTS.set(0);
        String instanceId = deployAndStart("VETO", "BLOCK", "BLOCK",
                Map.of("deptList", List.of(1L, 2L)));
        try {
            List<Task> tasks = taskService.createTaskQuery().processInstanceId(instanceId).list();
            assertThat(tasks).hasSize(2);
            String first = tasks.get(0).getId();
            String second = tasks.get(1).getId();
            taskService.complete(first, Map.of("outcome", "REJECTED"));
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).count()).isZero();
            assertThat(SETTLEMENTS.get()).as("一票否决单次结算").isEqualTo(1);
            // 第二条分支已被负向结算终止，不再存在可办理任务
            assertThat(taskService.createTaskQuery().taskId(second).count()).isZero();
        } finally {
            deleteQuietly(instanceId);
        }
    }

    @Test
    void shouldBlockOnEmptySourceUnlessExplicitProceed() {
        BaseException blocked = assertThrows(BaseException.class, () ->
                deployAndStart("ALL", "BLOCK", "BLOCK", Map.of("deptList", List.of())));
        assertThat(blocked.getCode()).isEqualTo(BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getCode());

        // 显式受控策略 PROCEED：零分支直接完成，进入终态（不静默——配置即明示）
        String instanceId = deployAndStart("ALL", "PROCEED", "BLOCK", Map.of("deptList", List.of()));
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).count()).isZero();
        assertThat(FREEZE_CALLS.get(instanceId + ":dyn")).isEmpty();
    }

    @Test
    void shouldBlockOnInvalidDeptAndMissingLeaderUnlessExplicitSkip() {
        BaseException invalidDept = assertThrows(BaseException.class, () ->
                deployAndStart("ALL", "BLOCK", "BLOCK", Map.of("deptList", List.of(1L, 99L))));
        assertThat(invalidDept.getCode()).isEqualTo(BpmErrorCode.DYNAMIC_BRANCH_LEADER_MISSING.getCode());

        BaseException missingLeader = assertThrows(BaseException.class, () ->
                deployAndStart("ALL", "BLOCK", "BLOCK", Map.of("deptList", List.of(7L))));
        assertThat(missingLeader.getCode()).isEqualTo(BpmErrorCode.DYNAMIC_BRANCH_LEADER_MISSING.getCode());

        // 显式受控策略 SKIP：无效对象逐条冻结记录 CANCELED 原因，有效分支照常运行
        String instanceId = deployAndStart("ALL", "BLOCK", "SKIP",
                Map.of("deptList", List.of(1L, 7L, 99L)));
        try {
            assertThat(FREEZE_CALLS.get(instanceId + ":dyn"))
                    .containsExactly("1@10", "7!LEADER_MISSING", "99!DEPT_INVALID");
            List<String> assignees = taskService.createTaskQuery().processInstanceId(instanceId).list()
                    .stream().map(Task::getAssignee).toList();
            assertThat(assignees).containsExactly("10");
        } finally {
            deleteQuietly(instanceId);
        }
    }

    @Test
    void shouldRejectSourceOverHardCap() {
        List<Long> many = new ArrayList<>();
        for (long i = 100; i < 200; i++) {
            many.add(i); // 均无负责人解析结果，但先触发数量上限
        }
        BaseException blocked = assertThrows(BaseException.class, () ->
                deployAndStart("ALL", "PROCEED", "SKIP", Map.of("deptList", many)));
        assertThat(blocked.getCode()).isEqualTo(BpmErrorCode.DYNAMIC_BRANCH_LIMIT_EXCEEDED.getCode());
    }

    // ==================== 公共装配 ====================

    private static void deleteQuietly(String instanceId) {
        try {
            runtimeService.deleteProcessInstance(instanceId, "cleanup");
        } catch (RuntimeException ignored) {
            // 实例已终局：无需清理
        }
    }

    private static String deployAndStart(String mode, String emptyStrategy, String invalidStrategy,
                                         Map<String, Object> variables) {
        GraphToBpmnTranslator translator = new GraphToBpmnTranslator(new ObjectMapper(),
                List.of(new DynamicParallelNodeTranslator(new ObjectMapper())));
        BpmnModel model = translator.translate(graph(mode, emptyStrategy, invalidStrategy));
        deployment = processEngine.getRepositoryService().createDeployment()
                .addBytes("i4-dynamic-parallel.bpmn20.xml", new BpmnXMLConverter().convertToXML(model))
                .name("i4-dynamic-parallel-" + System.nanoTime())
                .deploy();
        ProcessDefinition definition = processEngine.getRepositoryService()
                .createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        Map<String, Object> startVariables = new HashMap<>(variables);
        startVariables.putIfAbsent("tenantId", 1L);
        return runtimeService.startProcessInstanceById(definition.getId(), startVariables).getId();
    }

    private static ProcessGraph graph(String mode, String emptyStrategy, String invalidStrategy) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "动态并行");
        config.put("mode", mode);
        config.put("emptyStrategy", emptyStrategy);
        config.put("invalidStrategy", invalidStrategy);
        config.put("maxBranches", 50);
        config.put("source", Map.of("type", "VARIABLE", "value", "deptList"));
        return ProcessGraph.builder()
                .processKey("i4_dynamic_parallel")
                .elements(List.of(
                        node("start", "START"),
                        node("dyn", "DYNAMIC_PARALLEL", config),
                        node("end", "END"),
                        edge("e1", "start", "dyn"),
                        edge("e2", "dyn", "end")))
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
}
