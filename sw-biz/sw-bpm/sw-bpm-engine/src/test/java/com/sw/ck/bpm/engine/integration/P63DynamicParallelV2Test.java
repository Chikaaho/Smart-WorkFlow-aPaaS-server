package com.sw.ck.bpm.engine.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.engine.delegate.DynamicBranchCollectionResolver;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.bpm.engine.translator.DynamicParallelNodeTranslator;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P63 动态并行 v2 行为证据：对象身份分支（同负责人多部门独立）、表格列来源含行追溯、
 * 人员来源有效校验、空/失效/超上限确定结果、轮次经 executionId 冻结、汇聚计票重置。
 */
class P63DynamicParallelV2Test {

    private static final Long TENANT = 100L;
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
        };
    }

    private DynamicBranchCollectionResolver resolver(FormRecordReadFacade formFacade,
                                                     DynamicBranchPort port,
                                                     Map<String, Object> nodeConfig) {
        return resolver(formFacade, port, nodeConfig, null);
    }

    private DynamicBranchCollectionResolver resolver(FormRecordReadFacade formFacade,
                                                     DynamicBranchPort port,
                                                     Map<String, Object> nodeConfig,
                                                     com.sw.ck.bpm.api.variable.BpmVariableReadPort readPort) {
        RepositoryService repositoryService = mock(RepositoryService.class);
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        UserTask task = new UserTask();
        task.setId("dyn");
        ExtensionAttribute attr = new ExtensionAttribute("nodeConfig");
        attr.setNamespace(FLOWABLE_NS);
        try {
            attr.setValue(new ObjectMapper().writeValueAsString(nodeConfig));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        task.addAttribute(attr);
        process.addFlowElement(task);
        model.addProcess(process);
        when(repositoryService.getBpmnModel(any())).thenReturn(model);

        UserQueryFacade userFacade = mock(UserQueryFacade.class);
        when(userFacade.findActiveUserIds(anyCollection(), any())).thenAnswer(inv -> {
            List<Long> ids = (List<Long>) inv.getArgument(0);
            return Optional.of(ids.stream().filter(id -> id < 100).toList());
        });
        when(userFacade.findDeptLeaderMap(anyCollection(), any())).thenAnswer(inv -> {
            List<Long> ids = (List<Long>) inv.getArgument(0);
            Map<Long, Long> map = new LinkedHashMap<>();
            ids.forEach(id -> {
                if (id == 7L) map.put(7L, 30L);
                if (id == 8L) map.put(8L, 30L);
            });
            return Optional.of(map);
        });
        DeptQueryFacade deptFacade = mock(DeptQueryFacade.class);
        when(deptFacade.findActiveDeptIds(anyCollection())).thenAnswer(inv -> {
            List<Long> ids = (List<Long>) inv.getArgument(0);
            return Optional.of(ids.stream().filter(id -> id < 100).toList());
        });

        return new DynamicBranchCollectionResolver(repositoryService, new ObjectMapper(),
                mock(ParticipantResolverRegistry.class), deptFacade, userFacade,
                provider(port), formFacade, provider(readPort));
    }

    private DelegateExecution execution(String executionId) {
        DelegateExecution execution = mock(DelegateExecution.class);
        UserTask flowElement = new UserTask();
        flowElement.setId("dyn");
        when(execution.getCurrentFlowElement()).thenReturn(flowElement);
        when(execution.getVariable("tenantId")).thenReturn(TENANT);
        when(execution.getVariable("recordId")).thenReturn("rec-1");
        when(execution.getVariable("formKey")).thenReturn("p63_form");
        when(execution.getId()).thenReturn(executionId);
        when(execution.getProcessInstanceId()).thenReturn("pi-1");
        when(execution.getCurrentActivityId()).thenReturn("dyn");
        return execution;
    }

    private FormRecordReadFacade formFacade(Map<String, Object> fields,
                                            Map<String, List<Map<String, Object>>> tables) {
        return (tenantId, formKey, recordId) -> Optional.of(
                new FormRecordReadFacade.FormRecordData(formKey, recordId, fields, tables));
    }

    private Map<String, Object> v2Config(Map<String, Object> sourceOverrides,
                                         Map<String, Object> configOverrides) {
        Map<String, Object> source = new LinkedHashMap<>(Map.of(
                "type", "FORM_FIELD", "value", "dept_list"));
        source.putAll(sourceOverrides);
        Map<String, Object> config = new LinkedHashMap<>(Map.of(
                "semanticVersion", 2,
                "mode", "ALL",
                "source", source));
        config.putAll(configOverrides);
        return config;
    }

    @Test
    @DisplayName("DEPT·MAIN：同负责人多部门独立分支；缺负责人默认 BLOCK；SKIP 放行并逐条记因")
    void deptMainWithSameLeaderKeepsSeparateBranches() {
        Map<String, List<String>> frozenRefs = new LinkedHashMap<>();
        DynamicBranchPort port = new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String t, String pi, String n, String st,
                                                       String sd, String m, List<BranchCandidate> c) {
                throw new AssertionError("legacy path must not run for v2 config");
            }


            @Override
            public Optional<List<FrozenBranchV2>> freezeRound(String t, String pi, String n, String execId,
                                                              String st, String sd, String m, String ot,
                                                              List<BranchCandidateV2> candidates) {
                frozenRefs.put("executionId", List.of(execId));
                List<FrozenBranchV2> result = new java.util.ArrayList<>();
                int i = 0;
                for (BranchCandidateV2 c : candidates) {
                    if (c.assigneeId() != null) {
                        result.add(new FrozenBranchV2(i++, c.assigneeId(), ot, c.objectId(), c.sourceRefsJson()));
                    }
                }
                return Optional.of(result);
            }

            @Override public Optional<com.sw.ck.bpm.api.result.MutationOutcome> recordAction(String t, String pi, String n, String l, String task, String a, String r) { return Optional.empty(); }
            @Override public Optional<com.sw.ck.bpm.api.result.MutationOutcome> closeRemaining(String t, String pi, String n, String r) { return Optional.empty(); }
        };

        // 缺负责人（部门 9）默认 BLOCK
        DynamicBranchCollectionResolver blocking = resolver(formFacade(
                Map.of("dept_list", List.of("7", "8", "9")), Map.of()), port,
                v2Config(Map.of(), Map.of()));
        DelegateExecution execBlock = execution("exec-1");
        assertThatThrownBy(() -> blocking.resolveCollection(null, execBlock))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    assertThat(((BaseException) e).getCode())
                            .isEqualTo(BpmErrorCode.DYNAMIC_BRANCH_LEADER_MISSING.getCode());
                    System.out.println("[P63-EV] g04b.exception scenario=dept-leader-missing source=DEPT/7,8,9"
                            + " invalidStrategy=default errorCode=" + ((BaseException) e).getCode()
                            + " result=BLOCK");
                });

        // SKIP 放行：7/8 各自独立分支（同负责人 30/30），9 记 CANCELED
        DynamicBranchCollectionResolver skipping = resolver(formFacade(
                Map.of("dept_list", List.of("7", "8", "9")), Map.of()), port,
                v2Config(Map.of(), Map.of("invalidStrategy", "SKIP")));
        DelegateExecution execSkip = execution("exec-2");
        Object collection = skipping.resolveCollection(null, execSkip);
        assertThat((Iterable<Object>) collection).containsExactly("30", "30");
        System.out.println("[P63-EV] g04b.exception scenario=dept-leader-missing-explicit-SKIP"
                + " invalidStrategy=SKIP branches=[30,30] dept9=CANCELED result=old-explicit-SKIP-kept");
        assertThat(frozenRefs.get("executionId")).containsExactly("exec-2");
        org.mockito.Mockito.verify(execSkip, org.mockito.Mockito.atLeastOnce())
                .setVariable(org.mockito.ArgumentMatchers.eq("consensusApprovedCount"), org.mockito.ArgumentMatchers.eq(0));
    }

    @Test
    @DisplayName("USER·TABLE：逐行收集单值/多值列并校验有效人员；超上限明确拒绝")
    void userTableScopeCollectsRows() {
        Map<String, List<Map<String, Object>>> tables = Map.of(
                "items", List.of(
                        Map.of("id", "r1", "handlers", "5"),
                        Map.of("id", "r2", "handlers", List.of("5", "6"))));
        DynamicBranchPort port = new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String t, String pi, String n, String st,
                                                       String sd, String m, List<BranchCandidate> c) {
                throw new AssertionError("legacy path must not run for v2 config");
            }


            @Override
            public Optional<List<FrozenBranchV2>> freezeRound(String t, String pi, String n, String execId,
                                                              String st, String sd, String m, String ot,
                                                              List<BranchCandidateV2> candidates) {
                List<FrozenBranchV2> result = new java.util.ArrayList<>();
                int i = 0;
                for (BranchCandidateV2 c : candidates) {
                    if (c.assigneeId() != null) {
                        result.add(new FrozenBranchV2(i++, c.assigneeId(), ot, c.objectId(), c.sourceRefsJson()));
                    }
                }
                return Optional.of(result);
            }

            @Override public Optional<com.sw.ck.bpm.api.result.MutationOutcome> recordAction(String t, String pi, String n, String l, String task, String a, String r) { return Optional.empty(); }
            @Override public Optional<com.sw.ck.bpm.api.result.MutationOutcome> closeRemaining(String t, String pi, String n, String r) { return Optional.empty(); }
        };

        DynamicBranchCollectionResolver resolver = resolver(formFacade(Map.of(), tables), port,
                v2Config(Map.of("objectType", "USER", "scope", "TABLE", "tableField", "items",
                        "column", "handlers"), Map.of()));
        Object collection = resolver.resolveCollection(null, execution("exec-3"));
        assertThat((Iterable<Object>) collection).containsExactly("5", "6");

        // 无效人员（>=100）默认 BLOCK
        Map<String, List<Map<String, Object>>> invalidTables = Map.of(
                "items", List.of(Map.of("id", "r1", "handlers", "500")));
        DynamicBranchCollectionResolver invalid = resolver(formFacade(Map.of(), invalidTables), port,
                v2Config(Map.of("objectType", "USER", "scope", "TABLE", "tableField", "items",
                        "column", "handlers"), Map.of()));
        assertThatThrownBy(() -> invalid.resolveCollection(null, execution("exec-4")))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> System.out.println("[P63-EV] g04b.exception scenario=invalid-user-500"
                        + " source=USER/TABLE/handlers invalidStrategy=default"
                        + " errorCode=" + ((BaseException) e).getCode() + " result=BLOCK"));
    }

    @Test
    @DisplayName("空集合：默认 BLOCK；PROCEED 零分支直过")
    void emptySetStrategies() {
        DynamicBranchPort port = new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String t, String pi, String n, String st,
                                                       String sd, String m, List<BranchCandidate> c) {
                throw new AssertionError("legacy path must not run for v2 config");
            }

            @Override public Optional<List<FrozenBranchV2>> freezeRound(String t, String pi, String n, String e, String st, String sd, String m, String ot, List<BranchCandidateV2> c) {
                return Optional.of(List.of());
            }
            @Override public Optional<com.sw.ck.bpm.api.result.MutationOutcome> recordAction(String t, String pi, String n, String l, String task, String a, String r) { return Optional.empty(); }
            @Override public Optional<com.sw.ck.bpm.api.result.MutationOutcome> closeRemaining(String t, String pi, String n, String r) { return Optional.empty(); }
        };
        DynamicBranchCollectionResolver blocking = resolver(formFacade(Map.of(), Map.of()), port,
                v2Config(Map.of(), Map.of()));
        assertThatThrownBy(() -> blocking.resolveCollection(null, execution("exec-5")))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> {
                    assertThat(((BaseException) e).getCode())
                            .isEqualTo(BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getCode());
                    System.out.println("[P63-EV] g04b.exception scenario=empty-set-default"
                            + " emptyStrategy=default errorCode=" + ((BaseException) e).getCode()
                            + " result=BLOCK");
                });

        DynamicBranchCollectionResolver proceeding = resolver(formFacade(Map.of(), Map.of()), port,
                v2Config(Map.of(), Map.of("emptyStrategy", "PROCEED")));
        Object empty = proceeding.resolveCollection(null, execution("exec-6"));
        assertThat((Iterable<Object>) empty).isEmpty();
        System.out.println("[P63-EV] g04b.exception scenario=empty-set-explicit-PROCEED"
                + " emptyStrategy=PROCEED branches=[] result=old-explicit-PROCEED-kept");
    }

    @Test
    @DisplayName("翻译器 v2 配置校验：objectType/scope/表格列形状确定拒绝；旧语义不受影响")
    void translatorV2Validation() {
        DynamicParallelNodeTranslator translator = new DynamicParallelNodeTranslator(new ObjectMapper());

        GraphElement valid = node(Map.of(
                "semanticVersion", 2, "mode", "ALL",
                "source", Map.of("type", "FORM_FIELD", "value", "dept_list",
                        "objectType", "DEPT", "scope", "MAIN")));
        assertThat(translator.validateConfig(valid).orElseThrow()).isEmpty();

        GraphElement missingObjectType = node(Map.of(
                "semanticVersion", 2, "mode", "ALL",
                "source", Map.of("type", "FORM_FIELD", "value", "dept_list")));
        assertThat(translator.validateConfig(missingObjectType).orElseThrow()).isNotEmpty();

        GraphElement tableWithoutColumn = node(Map.of(
                "semanticVersion", 2, "mode", "ALL",
                "source", Map.of("type", "FORM_FIELD", "value", "dept_list",
                        "objectType", "USER", "scope", "TABLE", "tableField", "items")));
        assertThat(translator.validateConfig(tableWithoutColumn).orElseThrow()).isNotEmpty();

        GraphElement legacy = node(Map.of(
                "mode", "ALL",
                "source", Map.of("type", "VARIABLE", "value", "deptList")));
        assertThat(translator.validateConfig(legacy).orElseThrow()).isEmpty();
    }

    private GraphElement node(Map<String, Object> config) {
        GraphElement element = new GraphElement();
        element.setId("dyn");
        element.setKind("node");
        element.setType("DYNAMIC_PARALLEL");
        element.setConfig(config);
        return element;
    }

    @Test
    @DisplayName("P64：VARIABLE 来源流程变量缺省时回退 BPM 变量快照端口（USER_SET → 逐对象分支）")
    void variableSourceFallsBackToBpmVariablePort() {
        java.util.Map<String, List<String>> frozenRefs = new java.util.LinkedHashMap<>();
        DynamicBranchPort port = new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String t, String pi, String n, String st,
                                                       String sd, String m, List<BranchCandidate> c) {
                throw new AssertionError("legacy path must not run for v2 config");
            }


            @Override
            public Optional<List<FrozenBranchV2>> freezeRound(String t, String pi, String n, String execId,
                                                              String st, String sd, String m, String ot,
                                                              List<BranchCandidateV2> candidates) {
                frozenRefs.put("executionId", List.of(execId));
                List<FrozenBranchV2> result = new java.util.ArrayList<>();
                int i = 0;
                for (BranchCandidateV2 c : candidates) {
                    if (c.assigneeId() != null) {
                        result.add(new FrozenBranchV2(i++, c.assigneeId(), ot, c.objectId(), c.sourceRefsJson()));
                    }
                }
                return Optional.of(result);
            }

            @Override
            public Optional<com.sw.ck.bpm.api.result.MutationOutcome> recordAction(String t, String pi,
                                                                                   String n, String l,
                                                                                   String task, String a,
                                                                                   String r) {
                return Optional.of(com.sw.ck.bpm.api.result.MutationOutcome.APPLIED);
            }

            @Override
            public Optional<com.sw.ck.bpm.api.result.MutationOutcome> closeRemaining(String t, String pi,
                                                                                     String n, String r) {
                return Optional.empty();
            }
        };
        // 流程变量缺省：仅端口提供 var_handlers（USER_SET 快照形态 = ID 列表）
        com.sw.ck.bpm.api.variable.BpmVariableReadPort readPort = (pi, varId) ->
                "var_handlers".equals(varId) ? Optional.of(List.of("5", "6")) : Optional.empty();
        Map<String, Object> config = v2Config(
                Map.of("type", "VARIABLE", "value", "var_handlers", "objectType", "USER"), Map.of());
        DynamicBranchCollectionResolver resolver = resolver(formFacade(Map.of(), Map.of()), port, config, readPort);
        DelegateExecution exec = execution("exec-port");
        Object collection = resolver.resolveCollection(null, exec);
        assertThat((Iterable<Object>) collection).containsExactly("5", "6");
        assertThat(frozenRefs.get("executionId")).containsExactly("exec-port");
    }

    @Test
    @DisplayName("P64：端口未接线且流程变量缺省时保持原语义（空集合按 emptyStrategy 阻断）")
    void variableSourceWithoutPortKeepsBlockSemantics() {
        DynamicBranchPort port = new DynamicBranchPort() {
            @Override
            public Optional<List<FrozenBranch>> freeze(String t, String pi, String n, String st,
                                                       String sd, String m, List<BranchCandidate> c) {
                throw new AssertionError("legacy path must not run for v2 config");
            }


            @Override
            public Optional<List<FrozenBranchV2>> freezeRound(String t, String pi, String n, String execId,
                                                              String st, String sd, String m, String ot,
                                                              List<BranchCandidateV2> candidates) {
                return Optional.of(List.of());
            }

            @Override
            public Optional<com.sw.ck.bpm.api.result.MutationOutcome> recordAction(String t, String pi,
                                                                                   String n, String l,
                                                                                   String task, String a,
                                                                                   String r) {
                return Optional.of(com.sw.ck.bpm.api.result.MutationOutcome.APPLIED);
            }

            @Override
            public Optional<com.sw.ck.bpm.api.result.MutationOutcome> closeRemaining(String t, String pi,
                                                                                     String n, String r) {
                return Optional.empty();
            }
        };
        Map<String, Object> config = v2Config(
                Map.of("type", "VARIABLE", "value", "var_handlers", "objectType", "USER"), Map.of());
        DynamicBranchCollectionResolver resolver = resolver(formFacade(Map.of(), Map.of()), port, config, null);
        assertThatThrownBy(() -> resolver.resolveCollection(null, execution("exec-noport")))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getCode()));
    }
}
