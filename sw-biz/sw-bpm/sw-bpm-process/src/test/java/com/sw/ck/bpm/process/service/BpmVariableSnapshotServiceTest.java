package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTaskFormData;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BpmVariableSnapshotService} 运行快照解析单测（ADR-P64-001 §2）。
 * <p>
 * 覆盖：MAIN_FORM 标量/ROWS、NODE_FORM 集合并集去重与多任务标量冲突、ROWS 拼接来源追踪、
 * SYSTEM 白名单、必填缺失、类型不隐式转换；来源权限（读取主体=调用方租户、
 * 读取对象=实例自身绑定、非法/未知来源拒绝且零读取、白名单外排除且零读取）。
 * </p>
 */
@DisplayName("P64 变量运行快照解析测试")
class BpmVariableSnapshotServiceTest {

    private final NodeFormDataService nodeFormDataService = mock(NodeFormDataService.class);
    private final FormRecordReadFacade formRecordReadFacade = mock(FormRecordReadFacade.class);
    private final BpmVariableSnapshotService service =
            new BpmVariableSnapshotService(nodeFormDataService, formRecordReadFacade, new ObjectMapper());

    private void stubParseData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        when(nodeFormDataService.parseData(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> mapper.readValue((String) invocation.getArgument(0),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { }));
    }

    private BpmInstance instance() {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId("pi-1");
        instance.setProcessDefKey("def_main");
        instance.setBusinessKey("rec-1");
        instance.setFormKey("main_form");
        instance.setInitiatorId(7L);
        instance.setTenantId(9L);
        instance.setDefVersion(3);
        return instance;
    }

    private ProcessVariableDef def(String varId, String type, String source, String field) {
        return ProcessVariableDef.builder()
                .varId(varId).name(varId).type(type).source(source)
                .sourceField(field).sourceNodeKey(null).aggregation("NONE").nullable(false)
                .build();
    }

    @Test
    @DisplayName("MAIN_FORM 标量与 ROWS 读取；类型精确不隐式转换")
    void shouldResolveMainFormValues() {
        when(formRecordReadFacade.findRecord(9L, "main_form", "rec-1")).thenReturn(Optional.of(
                new FormRecordReadFacade.FormRecordData("main_form", "rec-1",
                        Map.of("title", "工单A", "ng_count", 3),
                        Map.of("items", List.of(Map.of("id", "r1", "name", "host-01"))))));
        List<ProcessVariableDef> defs = List.of(
                def("v_title", "STRING", "MAIN_FORM", "title"),
                def("v_count", "NUMBER", "MAIN_FORM", "ng_count"),
                def("v_rows", "ROWS", "MAIN_FORM", "items"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), defs, List.of("v_title", "v_count", "v_rows"), "node1", 1L);

        assertThat(result.failed()).isFalse();
        assertThat(result.values().get("v_title")).isEqualTo("工单A");
        assertThat(result.values().get("v_count")).isEqualTo(3);
        assertThat((List<?>) result.values().get("v_rows")).hasSize(1);
    }

    @Test
    @DisplayName("STRING 变量声明遇到 NUMBER 字段值：类型不隐式转换，按可诊断失败处理")
    void shouldNotImplicitConvertNumberToString() {
        when(formRecordReadFacade.findRecord(9L, "main_form", "rec-1")).thenReturn(Optional.of(
                new FormRecordReadFacade.FormRecordData("main_form", "rec-1",
                        Map.of("ng_count", 3), Map.of())));
        List<ProcessVariableDef> defs = List.of(def("v_ng", "STRING", "MAIN_FORM", "ng_count"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), defs, List.of("v_ng"), "node1", 1L);

        // 取值 null → 必填缺失（可诊断失败），不是 "3"
        assertThat(result.values().get("v_ng")).isNull();
        assertThat(result.missingRequired()).contains("v_ng");
    }

    @Test
    @DisplayName("NODE_FORM USER_SET 按稳定 ID 并集去重；标量多任务冲突可诊断")
    void shouldUnionNodeFormSetsAndFlagScalarConflicts() throws Exception {
        BpmTaskFormData row1 = new BpmTaskFormData();
        row1.setTaskId("t1");
        row1.setDataText("{\"handlers\":[\"11\",\"12\"],\"verdict\":\"PASS\"}");
        BpmTaskFormData row2 = new BpmTaskFormData();
        row2.setTaskId("t2");
        row2.setDataText("{\"handlers\":[\"12\",\"13\"],\"verdict\":\"NG\"}");
        stubParseData();
        when(nodeFormDataService.listSubmitted(eq(9L), eq("pi-1"), eq("node_qc"), eq(1L)))
                .thenReturn(List.of(row1, row2));

        ProcessVariableDef setDef = ProcessVariableDef.builder()
                .varId("v_handlers").type("USER_SET").source("NODE_FORM")
                .sourceNodeKey("node_qc").sourceFormField("handlers")
                .aggregation("UNION").nullable(false).build();
        ProcessVariableDef scalarDef = ProcessVariableDef.builder()
                .varId("v_verdict").type("STRING").source("NODE_FORM")
                .sourceNodeKey("node_qc").sourceFormField("verdict")
                .aggregation("NONE").nullable(false).build();

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), List.of(setDef, scalarDef), List.of("v_handlers", "v_verdict"),
                "node_qc", 1L);

        assertThat(result.values().get("v_handlers"))
                .isEqualTo(List.of("11", "12", "13"));
        assertThat(result.errors()).anyMatch(error -> error.contains("v_verdict"));
        assertThat(result.failed()).isTrue();
    }

    @Test
    @DisplayName("NODE_FORM ROWS 拼接保留来源任务追踪字段")
    void shouldConcatNodeFormRowsWithTrace() throws Exception {
        BpmTaskFormData row = new BpmTaskFormData();
        row.setTaskId("t9");
        row.setDataText("{\"hosts\":[{\"id\":\"row-1\",\"name\":\"host-01\"}]}");
        stubParseData();
        when(nodeFormDataService.listSubmitted(eq(9L), eq("pi-1"), eq("node_qc"), eq(1L)))
                .thenReturn(List.of(row));

        ProcessVariableDef rowsDef = ProcessVariableDef.builder()
                .varId("v_hosts").type("ROWS").source("NODE_FORM")
                .sourceNodeKey("node_qc").sourceFormField("hosts")
                .aggregation("CONCAT").nullable(false).build();

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), List.of(rowsDef), List.of("v_hosts"), "node_qc", 1L);

        assertThat(result.failed()).isFalse();
        List<?> rows = (List<?>) result.values().get("v_hosts");
        assertThat(rows).hasSize(1);
        assertThat(((Map<?, ?>) rows.get(0)).get("_sourceTaskId")).isEqualTo("t9");
        assertThat(((Map<?, ?>) rows.get(0)).get("id")).isEqualTo("row-1");
    }

    @Test
    @DisplayName("SYSTEM 只读白名单；白名单外字段返回 null 且必填缺失兜底")
    void shouldExposeSystemWhitelistOnly() {
        List<ProcessVariableDef> defs = List.of(
                def("v_inst", "STRING", "SYSTEM", "processInstanceId"),
                def("v_round", "NUMBER", "SYSTEM", "roundNo"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), defs, List.of("v_inst", "v_round"), "node1", 2L);

        assertThat(result.values().get("v_inst")).isEqualTo("pi-1");
        assertThat(result.values().get("v_round")).isEqualTo(2L);
    }

    @Test
    @DisplayName("未列入授权列表的变量不进入快照")
    void shouldOnlyIncludeAuthorizedVariables() {
        when(formRecordReadFacade.findRecord(9L, "main_form", "rec-1")).thenReturn(Optional.of(
                new FormRecordReadFacade.FormRecordData("main_form", "rec-1",
                        Map.of("secret", "x"), Map.of())));
        List<ProcessVariableDef> defs = List.of(def("v_secret", "STRING", "MAIN_FORM", "secret"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), defs, List.of(), "node1", 1L);

        assertThat(result.values()).doesNotContainKey("v_secret");
    }

    @Test
    @DisplayName("可空变量缺值返回 null 且不阻塞；必填缺失进入 missingRequired")
    void shouldHonorNullableFlag() {
        when(formRecordReadFacade.findRecord(9L, "main_form", "rec-1")).thenReturn(Optional.of(
                new FormRecordReadFacade.FormRecordData("main_form", "rec-1",
                        Map.of(), Map.of())));
        ProcessVariableDef nullable = ProcessVariableDef.builder()
                .varId("v_maybe").type("STRING").source("MAIN_FORM")
                .sourceField("nonexistent").nullable(true).build();
        ProcessVariableDef required = def("v_need", "STRING", "MAIN_FORM", "nonexistent");

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), List.of(nullable, required), List.of("v_maybe", "v_need"),
                "node1", 1L);

        assertThat(result.values().get("v_maybe")).isNull();
        assertThat(result.missingRequired()).containsExactly("v_need");
    }

    // ─── 复审06/提示05 P1-05a：来源权限（主体、来源绑定、非法拒绝、无越权取值） ───

    @Test
    @DisplayName("来源权限：未知来源拒绝且不读任何来源（无越权取值）")
    void shouldRejectUnknownVariableSourceWithoutFetch() {
        List<ProcessVariableDef> defs = List.of(def("v_bad", "STRING", "OTHER_FORM", "secret"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), defs, List.of("v_bad"), "node1", 1L);

        assertThat(result.errors()).anyMatch(error -> error.contains("未知变量来源"));
        assertThat(result.values()).doesNotContainKey("v_bad");
        verifyNoInteractions(formRecordReadFacade, nodeFormDataService);
    }

    @Test
    @DisplayName("来源权限：NODE_FORM 缺 sourceNodeKey 拒绝且不读取节点表单")
    void shouldRejectNodeFormSourceWithoutNodeKeyWithoutFetch() {
        List<ProcessVariableDef> defs = List.of(def("v_qc", "STRING", "NODE_FORM", "verdict"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), defs, List.of("v_qc"), "node1", 1L);

        assertThat(result.errors()).anyMatch(error -> error.contains("NODE_FORM 来源缺少 sourceNodeKey"));
        assertThat(result.values()).doesNotContainKey("v_qc");
        verify(nodeFormDataService, never()).listSubmitted(any(), any(), any(), any());
    }

    @Test
    @DisplayName("来源权限：MAIN_FORM 主体=调用方租户、对象=实例自身绑定（定义不可改写读取对象）")
    void shouldBindMainFormReadToCallerTenantAndInstanceBinding() {
        BpmInstance bound = instance();
        bound.setFormKey("main_form_bound");
        bound.setBusinessKey("rec-bound-42");
        when(formRecordReadFacade.findRecord(9L, "main_form_bound", "rec-bound-42")).thenReturn(Optional.of(
                new FormRecordReadFacade.FormRecordData("main_form_bound", "rec-bound-42",
                        Map.of("title", "工单A"), Map.of())));
        List<ProcessVariableDef> defs = List.of(def("v_title", "STRING", "MAIN_FORM", "title"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, bound, defs, List.of("v_title"), "node1", 1L);

        assertThat(result.values().get("v_title")).isEqualTo("工单A");
        // 恰一次读取，且读取对象=实例自身 formKey/businessKey（定义只有字段名，无表单/记录选择权）
        verify(formRecordReadFacade).findRecord(9L, "main_form_bound", "rec-bound-42");
        verifyNoMoreInteractions(formRecordReadFacade);
        verifyNoInteractions(nodeFormDataService);
    }

    @Test
    @DisplayName("来源权限：NODE_FORM 读取限定本实例（租户+实例ID+节点+轮次），不读他实例")
    void shouldScopeNodeFormReadToOwnInstance() throws Exception {
        BpmTaskFormData row = new BpmTaskFormData();
        row.setTaskId("t-own");
        row.setDataText("{\"handlers\":[\"2\"]}");
        stubParseData();
        when(nodeFormDataService.listSubmitted(eq(9L), eq("pi-1"), eq("node_qc"), eq(2L)))
                .thenReturn(List.of(row));
        ProcessVariableDef setDef = ProcessVariableDef.builder()
                .varId("v_handlers").type("USER_SET").source("NODE_FORM")
                .sourceNodeKey("node_qc").sourceFormField("handlers")
                .aggregation("UNION").nullable(false).build();

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), List.of(setDef), List.of("v_handlers"), "node_qc", 2L);

        assertThat(result.values().get("v_handlers")).isEqualTo(List.of("2"));
        verify(nodeFormDataService).listSubmitted(9L, "pi-1", "node_qc", 2L);
        verify(nodeFormDataService, never()).listSubmitted(
                any(), argThat((String id) -> !"pi-1".equals(id)), any(), any());
        verify(nodeFormDataService, never()).listSubmitted(
                argThat((Long tenant) -> !Long.valueOf(9L).equals(tenant)), any(), any(), any());
    }

    @Test
    @DisplayName("来源权限：SYSTEM 白名单外字段排除（null+必填缺失）且零读取")
    void shouldExcludeSystemFieldOutOfWhitelist() {
        List<ProcessVariableDef> defs = List.of(def("v_pass", "STRING", "SYSTEM", "dbPassword"));

        BpmVariableSnapshotService.SnapshotResult result = service.buildSnapshot(
                9L, instance(), defs, List.of("v_pass"), "node1", 1L);

        assertThat(result.values()).containsEntry("v_pass", null);
        assertThat(result.missingRequired()).containsExactly("v_pass");
        verifyNoInteractions(formRecordReadFacade, nodeFormDataService);
    }
}
