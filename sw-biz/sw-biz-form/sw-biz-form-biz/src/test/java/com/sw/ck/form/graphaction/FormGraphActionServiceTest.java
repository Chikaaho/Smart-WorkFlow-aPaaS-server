package com.sw.ck.form.graphaction;

import com.sw.ck.agent.api.port.AgentGraphInvocationResult;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.service.FormFieldValidator;
import com.sw.ck.form.service.FormGraphActionRules;
import com.sw.ck.form.service.FormGraphActionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link FormGraphActionService} F1-type 回填类型校验（planning-review-120-01 §1）：
 * 提交前类型不符阻止提交并点名字段；按钮触发跳过不回填并返回警告。
 */
@ExtendWith(MockitoExtension.class)
class FormGraphActionServiceTest {

    @Mock
    private org.springframework.beans.factory.ObjectProvider<com.sw.ck.agent.api.port.AgentGraphInvokePort> graphInvokePortProvider;
    @Mock
    private FormGraphActionRules graphActionRules;
    @Mock
    private FormFieldValidator formFieldValidator;

    @InjectMocks
    private FormGraphActionService service;

    private FormDefEntity def(long id) {
        FormDefEntity formDef = new FormDefEntity();
        formDef.setId(String.valueOf(id));
        formDef.setFormKey("fk_test");
        return formDef;
    }

    private void mockFieldTypes(long formId, FormFieldValidator.FieldDef... defs) {
        Map<String, FormFieldValidator.FieldDef> map = new java.util.LinkedHashMap<>();
        for (FormFieldValidator.FieldDef item : defs) {
            map.put(item.name(), item);
        }
        when(formFieldValidator.loadAndParseFieldDefs(eq(String.valueOf(formId)), any())).thenReturn(map);
    }

    private FormGraphActionRules.GraphAction actionWithOutputs(String... sources) {
        List<FormGraphActionRules.OutputMapping> mappings = java.util.Arrays.stream(sources)
                .map(source -> new FormGraphActionRules.OutputMapping(
                        source.equals("score") ? "riskScore" : source, source))
                .toList();
        return new FormGraphActionRules.GraphAction("ga1", "风控", "BEFORE_SUBMIT",
                11L, 3, List.of(), mappings, null);
    }

    private FormFieldValidator.FieldDef numberField(String name) {
        return new FormFieldValidator.FieldDef(name, "NUMBER", false, null,
                List.of(), null, name, false, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("提交前触发：数字字段收到文本 → 阻止提交并点名字段")
    void beforeSubmitRejectsTypeConflict() {
        FormDefEntity formDef = def(9L);
        mockFieldTypes(9L, numberField("riskScore"));
        FormGraphActionRules.GraphAction action = actionWithOutputs("score");
        when(graphActionRules.parse(formDef)).thenReturn(List.of(action));
        var port = org.mockito.Mockito.mock(com.sw.ck.agent.api.port.AgentGraphInvokePort.class);
        when(graphInvokePortProvider.getIfAvailable()).thenReturn(port);
        com.sw.ck.security.holder.LoginUserHolder.set(systemUser());
        when(port.invoke(any())).thenReturn(new AgentGraphInvocationResult(
                true, 5L, Map.of("score", "abc"), null, null, 10L));

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        assertThatThrownBy(() -> service.runBeforeSubmit(formDef, data))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class)
                .hasMessageContaining("riskScore")
                .hasMessageContaining("非数值");
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    @Test
    @DisplayName("按钮触发：类型不符的输出跳过不回填并返回警告；合法输出照常回填")
    void buttonSkipsConflictAndWarns() {
        FormDefEntity formDef = def(9L);
        mockFieldTypes(9L, numberField("riskScore"),
                new FormFieldValidator.FieldDef("city", "TEXT", false, null,
                        List.of(), null, "城市", false, null, null, null, null, null, null, null));
        FormGraphActionRules.GraphAction action = new FormGraphActionRules.GraphAction(
                "ga1", "风控", "BUTTON", 11L, 3, List.of(),
                List.of(new FormGraphActionRules.OutputMapping("riskScore", "score"),
                        new FormGraphActionRules.OutputMapping("city", "cityName")),
                null);
        when(graphActionRules.parse(formDef)).thenReturn(List.of(action));
        var port = org.mockito.Mockito.mock(com.sw.ck.agent.api.port.AgentGraphInvokePort.class);
        when(graphInvokePortProvider.getIfAvailable()).thenReturn(port);
        com.sw.ck.security.holder.LoginUserHolder.set(systemUser());
        try {
            when(port.invoke(any())).thenReturn(new AgentGraphInvocationResult(
                    true, 5L,
                    Map.of("score", "abc", "cityName", "北京"), null, null, 10L));
            FormGraphActionService.ButtonResult result = service.runButton(formDef, "ga1", null, Map.of());
            assertThat(result.outputs()).containsOnlyKeys("city").containsEntry("city", "北京");
            assertThat(result.warnings()).hasSize(1);
            assertThat(result.warnings().get(0)).contains("riskScore").contains("非数值");
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    private com.sw.ck.security.holder.LoginUser systemUser() {
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(0L);
        user.setTenantId(1L);
        user.setUsername("test");
        return user;
    }
}
