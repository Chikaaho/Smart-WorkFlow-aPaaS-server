package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BpmNodeFormController} 越权边界单测（A01：未授权访问/写入拒绝）。
 * <p>
 * 语义与 TaskActionService 办理校验同源：任务办理人（assignee/candidate，
 * canHandle fail closed）或实例发起人可读；写入仅限办理人。
 * </p>
 */
@DisplayName("P64 节点表单控制器越权边界测试")
class BpmNodeFormControllerTest {

    private final BpmTaskFacade bpmTaskFacade = mock(BpmTaskFacade.class);
    private final BpmInstanceService bpmInstanceService = mock(BpmInstanceService.class);
    private final NodeFormDataService nodeFormDataService = mock(NodeFormDataService.class);
    private final BpmNodeFormController controller = new BpmNodeFormController(
            bpmTaskFacade, bpmInstanceService, nodeFormDataService, new ObjectMapper());

    @BeforeEach
    void setUp() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(2L);
        loginUser.setTenantId(9L);
        LoginUserHolder.set(loginUser);
        // 绑定版本定义可用（缺失分支由 shouldRejectDiagnosablyWhenBoundSnapshotMissing 覆盖）
        when(nodeFormDataService.loadBoundDefinition(any(), any()))
                .thenReturn(Optional.of("{\"fields\":[{\"name\":\"f\",\"type\":\"TEXT\"}]}"));
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private BpmTaskDTO task(String taskId, String assignee) {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId(taskId);
        task.setTaskDefinitionKey("node_qc");
        task.setProcessInstanceId("pi-1");
        task.setProcessDefinitionKey("def_main");
        task.setAssignee(assignee);
        return task;
    }

    private BpmInstance instance(Long initiatorId) {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId("pi-1");
        instance.setProcessDefKey("def_main");
        instance.setDefVersion(2);
        instance.setInitiatorId(initiatorId);
        instance.setTenantId(9L);
        return instance;
    }

    private void stubTask(BpmTaskDTO task, BpmInstance instance) {
        when(bpmTaskFacade.getTask(task.getTaskId())).thenReturn(Optional.of(task));
        when(bpmInstanceService.findByProcessInstanceId("pi-1")).thenReturn(Optional.of(instance));
    }

    @Test
    @DisplayName("无关用户读取节点表单：FORBIDDEN 拒绝（非 assignee/不可办理/非发起人）")
    void shouldRejectUnrelatedUserRead() {
        BpmTaskDTO task = task("task-1", "11");
        stubTask(task, instance(7L));
        when(bpmTaskFacade.canHandle("task-1", "2")).thenReturn(Optional.of(Boolean.FALSE));

        assertThatThrownBy(() -> controller.getTaskNodeForm("task-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    @DisplayName("canHandle 无法判定：fail closed 拒绝读取")
    void shouldFailClosedWhenCanHandleUndecidable() {
        BpmTaskDTO task = task("task-1", "11");
        stubTask(task, instance(7L));
        when(bpmTaskFacade.canHandle("task-1", "2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getTaskNodeForm("task-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    @DisplayName("实例发起人可读取；但发起人写草稿被拒绝（写入仅限办理人）")
    void shouldAllowInitiatorReadButRejectInitiatorWrite() {
        BpmTaskDTO task = task("task-1", "11");
        stubTask(task, instance(2L));
        when(bpmTaskFacade.canHandle("task-1", "2")).thenReturn(Optional.of(Boolean.FALSE));
        when(nodeFormDataService.resolveBinding("def_main", 2, "node_qc"))
                .thenReturn(Optional.of(new NodeFormDataService.NodeFormBinding("qc_form", "3", "质检处理单")));

        R<Map<String, Object>> read = controller.getTaskNodeForm("task-1");
        assertThat(read.getData()).containsEntry("bound", true);

        assertThatThrownBy(() -> controller.saveDraft("task-1", Map.of("data", Map.of())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("无权填写");
    }

    @Test
    @DisplayName("任务办理人（candidate，canHandle=true）可读可写")
    void shouldAllowHandlerReadWrite() {
        BpmTaskDTO task = task("task-1", null);
        stubTask(task, instance(7L));
        when(bpmTaskFacade.canHandle("task-1", "2")).thenReturn(Optional.of(Boolean.TRUE));
        when(nodeFormDataService.resolveBinding("def_main", 2, "node_qc"))
                .thenReturn(Optional.of(new NodeFormDataService.NodeFormBinding("qc_form", "3", "质检处理单")));

        assertThat(controller.getTaskNodeForm("task-1").getData()).containsEntry("bound", true);

        when(nodeFormDataService.saveDraft(eq("pi-1"), eq("def_main"), eq("node_qc"), eq("task-1"),
                any(), any(), any())).thenReturn(1L);
        R<Long> draft = controller.saveDraft("task-1", Map.of("data", Map.of("f", 1)));
        assertThat(draft.getData()).isEqualTo(1L);
    }

    @Test
    @DisplayName("assignee 直接匹配：无需 canHandle 即可读可写")
    void shouldAllowAssigneeReadWrite() {
        BpmTaskDTO task = task("task-1", "2");
        stubTask(task, instance(7L));
        when(bpmTaskFacade.canHandle("task-1", "2")).thenReturn(Optional.of(Boolean.FALSE));
        when(nodeFormDataService.resolveBinding("def_main", 2, "node_qc"))
                .thenReturn(Optional.of(new NodeFormDataService.NodeFormBinding("qc_form", "3", "质检处理单")));

        assertThat(controller.getTaskNodeForm("task-1").getData()).containsEntry("bound", true);

        when(nodeFormDataService.saveDraft(any(), any(), any(), any(), any(), any(), any())).thenReturn(9L);
        assertThat(controller.saveDraft("task-1", Map.of("data", Map.of())).getData()).isEqualTo(9L);
    }

    @Test
    @DisplayName("节点表单 definition 以结构化对象返回（fields 供办理页渲染，非 JSON 字符串）")
    void shouldReturnStructuredDefinitionObject() {
        BpmTaskDTO task = task("task-1", "2");
        stubTask(task, instance(7L));
        when(bpmTaskFacade.canHandle("task-1", "2")).thenReturn(Optional.of(Boolean.TRUE));
        when(nodeFormDataService.resolveBinding("def_main", 2, "node_qc"))
                .thenReturn(Optional.of(new NodeFormDataService.NodeFormBinding("qc_form", "3", "质检处理单")));

        R<Map<String, Object>> read = controller.getTaskNodeForm("task-1");
        Object definition = read.getData().get("definition");
        assertThat(definition).as("definition 必须是对象（契约形状），不是 JSON 字符串")
                .isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) definition).get("fields")).isInstanceOf(java.util.List.class);
    }

    @Test
    @DisplayName("绑定版本快照缺失：读取可诊断拒绝（不静默渲染最新定义）")
    void shouldRejectDiagnosablyWhenBoundSnapshotMissing() {
        BpmTaskDTO task = task("task-1", "2");
        stubTask(task, instance(7L));
        when(bpmTaskFacade.canHandle("task-1", "2")).thenReturn(Optional.of(Boolean.TRUE));
        when(nodeFormDataService.resolveBinding("def_main", 2, "node_qc"))
                .thenReturn(Optional.of(new NodeFormDataService.NodeFormBinding("qc_form", "3", "质检处理单")));
        when(nodeFormDataService.loadBoundDefinition("qc_form", 3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getTaskNodeForm("task-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("绑定版本快照缺失")
                .hasMessageContaining("qc_form@v3");
    }
}
