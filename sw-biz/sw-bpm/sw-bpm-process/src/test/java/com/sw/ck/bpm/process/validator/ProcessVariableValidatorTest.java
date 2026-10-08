package com.sw.ck.bpm.process.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProcessVariableValidator} 发布校验单测（ADR-P64-001 §2-§4）。
 * <p>
 * 覆盖：合法配置通过；varId 重复/集合类型缺 UNION/同类型同值重复分支/脚本语法错误/
 * 目标绑定不一致/映射目标字段缺失各配置拒绝。
 * </p>
 */
@DisplayName("P64 变量与触发器发布校验测试")
class ProcessVariableValidatorTest {

    private final FormDefinitionService formDefinitionService = mock(FormDefinitionService.class);
    private final BpmFormBindingService formBindingService = mock(BpmFormBindingService.class);
    private final com.sw.ck.bpm.api.script.BpmScriptEvaluatePort scriptPort =
            mock(com.sw.ck.bpm.api.script.BpmScriptEvaluatePort.class);
    { org.mockito.Mockito.when(scriptPort.validate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new BpmScriptEvaluatePort.ScriptOutcome("OK", null, null, null, 0L)); }

    private final ProcessVariableValidator validator = new ProcessVariableValidator(
            formDefinitionService, formBindingService, scriptPort, new ObjectMapper());

    private static final String MAIN_DEFINITION = "{\"fields\":["
            + "{\"name\":\"handlers\",\"type\":\"USER\",\"multiple\":true},"
            + "{\"name\":\"title\",\"type\":\"TEXT\"},"
            + "{\"name\":\"ng_count\",\"type\":\"NUMBER\"}]}";
    private static final String TARGET_DEFINITION = "{\"fields\":["
            + "{\"name\":\"owner\",\"type\":\"USER\"},"
            + "{\"name\":\"reason\",\"type\":\"TEXT\"}]}";

    private void stubForms() {
        stubForm("main_form", MAIN_DEFINITION);
        stubForm("target_form", TARGET_DEFINITION);
        BpmFormBinding binding = new BpmFormBinding();
        binding.setFormKey("target_form");
        binding.setProcessDefKey("def_target");
        binding.setActive(true);
        when(formBindingService.findActiveByFormKey("target_form")).thenReturn(List.of(binding));
    }

    private void stubForm(String formKey, String definition) {
        FormDefDTO def = new FormDefDTO();
        def.setFormKey(formKey);
        def.setStatus("PUBLISHED");
        def.setFormVersion(2);
        when(formDefinitionService.getFormDef(formKey)).thenReturn(Optional.of(def));
        when(formDefinitionService.getFormDefinition(formKey)).thenReturn(Optional.of(definition));
    }

    private ProcessVariableDef setVar(String varId) {
        return ProcessVariableDef.builder()
                .varId(varId).type("USER_SET").source("MAIN_FORM")
                .sourceField("handlers").aggregation("UNION").nullable(false).build();
    }

    private TriggerConfig.TriggerBranch branch(String id, String type, String value,
                                               ActionConfig... actions) {
        return TriggerConfig.TriggerBranch.builder()
                .branchId(id).matchType(type).matchValue(value)
                .actions(List.of(actions)).build();
    }

    private ActionConfig eachAction() {
        return ActionConfig.builder()
                .actionId("act-1").type("START_EACH").sourceVariable("v_set")
                .targetProcessDefKey("def_target").targetFormKey("target_form")
                .mapping(List.of(ActionConfig.ActionMapping.builder()
                        .targetField("owner").itemField("id").build()))
                .build();
    }

    /** 触发器源节点元素（node_qc），供触发器 sourceNodeKey 存在性校验。 */
    private GraphElement qcNode() {
        return GraphElement.builder().id("node_qc").kind("node").type("APPROVAL")
                .config(Map.of("name", "质检")).build();
    }

    @Test
    @DisplayName("合法配置：校验零错误")
    void shouldPassValidConfiguration() {
        stubForms();
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(qcNode()))
                .variables(List.of(setVar("v_set")))
                .triggers(List.of(TriggerConfig.builder()
                        .triggerId("trg-1").event("NODE_ROUND_COMPLETED").nodeKey("node_qc")
                        .script("return 1;").variables(List.of("v_set"))
                        .branches(List.of(branch("b1", "NUMBER", "1", eachAction())))
                        .build()))
                .build();

        List<GraphValidationError> errors = validator.validate(graph);
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("集合类型未配 UNION / varId 重复 / 授权未定义变量：拒绝")
    void shouldRejectInvalidVariableConfigs() {
        stubForms();
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(qcNode()))
                .variables(List.of(
                        setVar("v_set"),
                        ProcessVariableDef.builder()
                                .varId("v_set").type("USER_SET").source("MAIN_FORM")
                                .sourceField("handlers").aggregation("UNION").build(),
                        ProcessVariableDef.builder()
                                .varId("v_scalar").type("STRING").source("MAIN_FORM")
                                .sourceField("title").aggregation("NONE").build()))
                .triggers(List.of(TriggerConfig.builder()
                        .triggerId("trg-1").event("NODE_ROUND_COMPLETED").nodeKey("node_qc")
                        .script("return 1;").variables(List.of("v_ghost"))
                        .branches(List.of()).build()))
                .build();

        List<GraphValidationError> errors = validator.validate(graph);
        assertThat(errors).anyMatch(error -> error.getMessage().contains("varId 重复"));
        assertThat(errors).anyMatch(error -> error.getMessage().contains("v_ghost"));
    }

    @Test
    @DisplayName("同类型同值重复分支：发布拒绝（方向 §3.2）")
    void shouldRejectDuplicateMatchBranches() {
        stubForms();
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(qcNode()))
                .variables(List.of(setVar("v_set")))
                .triggers(List.of(TriggerConfig.builder()
                        .triggerId("trg-1").event("TASK_SUBMITTED").nodeKey("node_qc")
                        .script("return 1;").variables(List.of("v_set"))
                        .branches(List.of(
                                branch("b1", "NUMBER", "1", eachAction()),
                                branch("b2", "NUMBER", "1", eachAction())))
                        .build()))
                .build();

        List<GraphValidationError> errors = validator.validate(graph);
        assertThat(errors).anyMatch(error -> error.getMessage().contains("同类型同值重复分支"));
    }

    @Test
    @DisplayName("脚本语法错误：发布拒绝")
    void shouldRejectUncompilableScript() {
        stubForms();
        when(scriptPort.validate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new BpmScriptEvaluatePort.ScriptOutcome(
                        BpmScriptEvaluatePort.ScriptOutcome.KIND_SCRIPT_ERROR,
                        null, null, "语法错误", 0L));
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(qcNode()))
                .variables(List.of(setVar("v_set")))
                .triggers(List.of(TriggerConfig.builder()
                        .triggerId("trg-1").event("TASK_SUBMITTED").nodeKey("node_qc")
                        .script("return ???;").variables(List.of("v_set"))
                        .branches(List.of()).build()))
                .build();

        List<GraphValidationError> errors = validator.validate(graph);
        assertThat(errors).anyMatch(error -> error.getMessage().contains("脚本不可编译"));
    }

    @Test
    @DisplayName("目标表单绑定缺失/与 targetProcessDefKey 不一致：拒绝")
    void shouldRejectInconsistentActionTarget() {
        stubForms();
        when(formBindingService.findActiveByFormKey("target_form")).thenReturn(List.of());
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(qcNode()))
                .variables(List.of(setVar("v_set")))
                .triggers(List.of(TriggerConfig.builder()
                        .triggerId("trg-1").event("TASK_SUBMITTED").nodeKey("node_qc")
                        .script("return 1;").variables(List.of("v_set"))
                        .branches(List.of(branch("b1", "NUMBER", "1", eachAction())))
                        .build()))
                .build();

        List<GraphValidationError> errors = validator.validate(graph);
        assertThat(errors).anyMatch(error -> error.getMessage().contains("唯一启用流程绑定"));
    }

    @Test
    @DisplayName("映射目标字段不存在 / 缺少来源：拒绝")
    void shouldRejectInvalidMappings() {
        stubForms();
        ActionConfig bad = ActionConfig.builder()
                .actionId("act-1").type("START_EACH").sourceVariable("v_set")
                .targetProcessDefKey("def_target").targetFormKey("target_form")
                .mapping(List.of(
                        ActionConfig.ActionMapping.builder().targetField("ghost_field")
                                .itemField("id").build(),
                        ActionConfig.ActionMapping.builder().targetField("reason").build()))
                .build();
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(qcNode()))
                .variables(List.of(setVar("v_set")))
                .triggers(List.of(TriggerConfig.builder()
                        .triggerId("trg-1").event("TASK_SUBMITTED").nodeKey("node_qc")
                        .script("return 1;").variables(List.of("v_set"))
                        .branches(List.of(branch("b1", "NUMBER", "1", bad)))
                        .build()))
                .build();

        List<GraphValidationError> errors = validator.validate(graph);
        assertThat(errors).anyMatch(error -> error.getMessage().contains("目标表单缺少字段 ghost_field"));
        assertThat(errors).anyMatch(error -> error.getMessage().contains("缺少来源"));
    }

    @Test
    @DisplayName("无变量无触发器无节点表单的旧图：零校验零错误（A12）")
    void shouldNoOpOnLegacyGraph() {
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(GraphElement.builder().id("n1").kind("node")
                        .type("APPROVAL").config(Map.of("name", "审批")).build()))
                .build();
        assertThat(validator.validate(graph)).isEmpty();
    }

    @Test
    @DisplayName("非人工节点绑定节点表单：拒绝")
    void shouldRejectNodeFormOnUnsupportedNodeType() {
        stubForms();
        ProcessGraph graph = ProcessGraph.builder()
                .formKey("main_form")
                .elements(List.of(GraphElement.builder().id("n1").kind("node")
                        .type("NOTIFICATION")
                        .config(Map.of("nodeForm", Map.of("formKey", "main_form"))).build()))
                .build();
        List<GraphValidationError> errors = validator.validate(graph);
        assertThat(errors).anyMatch(error -> error.getMessage().contains("不支持绑定业务表单"));
    }
}
