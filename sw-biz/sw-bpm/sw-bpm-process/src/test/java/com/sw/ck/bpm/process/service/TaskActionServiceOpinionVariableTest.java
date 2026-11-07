package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.security.holder.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 0.3.1 意见驱动流转（a）：审批意见字段写入流程变量。
 * <p>
 * 覆盖：opinion 最新一条对象、opinion_&lt;字段&gt; 平铺、意见字段与主表单同名时回写 formData、
 * 空意见零写入、写失败上抛（调用方结算）。
 * </p>
 */
@DisplayName("审批意见写入流程变量（0.3.1 意见驱动 a）")
class TaskActionServiceOpinionVariableTest {

    private final BpmTaskFacade bpmTaskFacade = mock(BpmTaskFacade.class);

    private final TaskActionService service = new TaskActionService(
            bpmTaskFacade, null, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(9L);
        loginUser.setTenantId(1L);
        com.sw.ck.security.holder.LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    private BpmTaskDTO task() {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("task-1");
        task.setProcessInstanceId("pi-1");
        task.setTaskDefinitionKey("node_head");
        return task;
    }

    private ApprovalActionRequest request(Map<String, Object> data) {
        ApprovalActionRequest request = new ApprovalActionRequest();
        request.setAction(ApprovalAction.APPROVE);
        request.setOpinionFormId("drill_head_opinion");
        request.setOpinionFormVersion("1");
        request.setOpinionData(data);
        return request;
    }

    @Test
    @DisplayName("写入 opinion 对象与 opinion_ 平铺变量（nodeKey/actorId/fields 齐全）")
    void shouldWriteOpinionObjectAndFlatVariables() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planReadiness", "COMPLETE");
        data.put("fieldCommander", "91103");
        data.put("rollbackNotes", "回退脚本已在 11 月 3 日预演通过");

        service.writeOpinionVariables(task(), currentUser(), request(data));

        org.mockito.ArgumentCaptor<Map<String, Object>> opinionCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(bpmTaskFacade).setVariable(eq("pi-1"), eq("opinion"), opinionCaptor.capture());
        Map<String, Object> opinion = opinionCaptor.getValue();
        assertThat(opinion).containsEntry("nodeKey", "node_head")
                .containsEntry("actorId", "9")
                .containsEntry("taskId", "task-1")
                .containsEntry("formId", "drill_head_opinion");
        @SuppressWarnings("unchecked") Map<String, Object> fields0 = (Map<String, Object>) opinion.get("fields");
        assertThat(fields0)
                .containsEntry("planReadiness", "COMPLETE")
                .containsEntry("fieldCommander", "91103");
        verify(bpmTaskFacade).setVariable("pi-1", "opinion_planReadiness", "COMPLETE");
        verify(bpmTaskFacade).setVariable("pi-1", "opinion_fieldCommander", "91103");
        verify(bpmTaskFacade).setVariable("pi-1", "opinion_rollbackNotes",
                "回退脚本已在 11 月 3 日预演通过");
    }

    @Test
    @DisplayName("意见字段与主表单同名时回写 formData（预约 dueField 与 form. 条件即用新值）")
    void shouldMergeSameNameFieldsIntoFormData() {
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("drillName", "2026 年 Q4 华东分公司同城灾备切换演练");
        formData.put("plannedStart", "2026-11-10 22:00");
        when(bpmTaskFacade.getVariables("pi-1")).thenReturn(Optional.of(Map.of("formData", formData)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionDecision", "ADJUST");
        data.put("plannedStart", "2026-11-10 23:30");

        service.writeOpinionVariables(task(), currentUser(), request(data));

        org.mockito.ArgumentCaptor<Map<String, Object>> mergedCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(bpmTaskFacade).setVariable(eq("pi-1"), eq("formData"), mergedCaptor.capture());
        assertThat(mergedCaptor.getValue())
                .containsEntry("drillName", "2026 年 Q4 华东分公司同城灾备切换演练")
                .containsEntry("plannedStart", "2026-11-10 23:30");
        verify(bpmTaskFacade).setVariable("pi-1", "opinion_executionDecision", "ADJUST");
    }

    @Test
    @DisplayName("意见字段与主表单无同名键时不回写 formData")
    void shouldSkipFormDataMergeWithoutSameNameKey() {
        when(bpmTaskFacade.getVariables("pi-1")).thenReturn(Optional.of(Map.of(
                "formData", Map.of("drillName", "演练"), "submitter", "1")));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planReadiness", "COMPLETE");

        service.writeOpinionVariables(task(), currentUser(), request(data));

        verify(bpmTaskFacade, org.mockito.Mockito.never())
                .setVariable(eq("pi-1"), eq("formData"), any());
        verify(bpmTaskFacade).setVariable(eq("pi-1"), eq("opinion"), any());
    }

    @Test
    @DisplayName("空意见零写入")
    void shouldWriteNothingWithoutOpinionData() {
        service.writeOpinionVariables(task(), currentUser(), request(null));
        service.writeOpinionVariables(task(), currentUser(), request(Map.of()));

        verify(bpmTaskFacade, org.mockito.Mockito.never())
                .setVariable(eq("pi-1"), eq("opinion"), any());
        verify(bpmTaskFacade, org.mockito.Mockito.never()).getVariables("pi-1");
    }

    private LoginUser currentUser() {
        return com.sw.ck.security.holder.LoginUserHolder.get();
    }
}
