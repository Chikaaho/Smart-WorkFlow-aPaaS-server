package com.sw.ck.bpm.engine.facade;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.common.exception.BaseException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 任务 DTO 必须读取 processDefinitionId 对应部署的冻结 nodeConfig，不回读当前设计图。 */
@DisplayName("任务冻结节点配置读取")
class BpmTaskFacadeImplNodeConfigTest {

    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    private final TaskService taskService = mock(TaskService.class);
    private final TaskQuery taskQuery = mock(TaskQuery.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class);
    private final ProcessInstanceQuery processInstanceQuery = mock(ProcessInstanceQuery.class);
    private final RepositoryService repositoryService = mock(RepositoryService.class);
    private final ProcessDefinitionQuery processDefinitionQuery = mock(ProcessDefinitionQuery.class);
    private final HistoryService historyService = mock(HistoryService.class);
    private final HistoricTaskInstanceQuery historicTaskQuery = mock(HistoricTaskInstanceQuery.class);
    private final BpmTaskFacadeImpl facade = new BpmTaskFacadeImpl(
            taskService, runtimeService, repositoryService, historyService);

    @BeforeEach
    void setUp() {
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(anyString())).thenReturn(taskQuery);
        when(taskService.getIdentityLinksForTask(anyString())).thenReturn(List.of());

        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getBusinessKey()).thenReturn("record-1");
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);
        when(processInstanceQuery.processInstanceId(anyString())).thenReturn(processInstanceQuery);
        when(processInstanceQuery.singleResult()).thenReturn(processInstance);

        when(repositoryService.createProcessDefinitionQuery()).thenReturn(processDefinitionQuery);
        when(processDefinitionQuery.processDefinitionId(anyString())).thenReturn(processDefinitionQuery);
        when(processDefinitionQuery.singleResult()).thenReturn(null);

        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.taskId(anyString())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.finished()).thenReturn(historicTaskQuery);
    }

    @Test
    @DisplayName("不同部署版本的运行任务只读取各自冻结配置")
    void runtimeTasksReadTheirOwnDeploymentConfig() {
        Task newTask = task("task-new", "deployment-new");
        Task oldTask = task("task-old", "deployment-old");
        when(taskQuery.singleResult()).thenReturn(newTask, oldTask);
        freezeNodeConfig("deployment-new", "approve",
                "{\"allowedActions\":[\"APPROVE\",\"RETURN\"],\"returnTargets\":[\"draft\"]}");
        freezeNodeConfig("deployment-old", "approve", null);

        BpmTaskDTO current = facade.getTask("task-new").orElseThrow();
        BpmTaskDTO legacy = facade.getTask("task-old").orElseThrow();

        assertThat(current.getNodeConfig()).containsEntry("allowedActions", List.of("APPROVE", "RETURN"));
        assertThat(current.getNodeConfig()).containsEntry("returnTargets", List.of("draft"));
        assertThat(legacy.getNodeConfig()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("历史任务同样读取其部署版本冻结配置")
    void historicTaskReadsFrozenDeploymentConfig() {
        HistoricTaskInstance task = historicTask("history-1", "deployment-history");
        when(historicTaskQuery.singleResult()).thenReturn(task);
        freezeNodeConfig("deployment-history", "approve",
                "{\"allowedActions\":[\"APPROVE\"],\"opinionForm\":{\"title\":\"历史意见\"}}");

        BpmTaskDTO dto = facade.getHistoricTask("history-1").orElseThrow();

        assertThat(dto.getNodeConfig()).containsEntry("allowedActions", List.of("APPROVE"));
        assertThat(dto.getNodeConfig().get("opinionForm"))
                .isEqualTo(Map.of("title", "历史意见"));
    }

    @Test
    @DisplayName("非法冻结 JSON fail-closed，不使用当前图兜底")
    void malformedFrozenConfigFailsClosed() {
        Task badTask = task("task-bad", "deployment-bad");
        when(taskQuery.singleResult()).thenReturn(badTask);
        freezeNodeConfig("deployment-bad", "approve", "{not-json");

        assertThatThrownBy(() -> facade.getTask("task-bad"))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getCode()).isEqualTo(2106));
    }

    @ParameterizedTest(name = "nodeConfig={0} 不是对象时 fail-closed")
    @ValueSource(strings = {"null", "[]", "[\"APPROVE\"]"})
    @DisplayName("显式 null 或数组 JSON 不被当作旧部署缺省兼容")
    void nonObjectFrozenConfigFailsClosed(String json) {
        Task nonObjectTask = task("task-non-object", "deployment-non-object");
        when(taskQuery.singleResult()).thenReturn(nonObjectTask);
        freezeNodeConfig("deployment-non-object", "approve", json);

        assertThatThrownBy(() -> facade.getTask("task-non-object"))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getCode()).isEqualTo(2106));
    }

    private Task task(String taskId, String processDefinitionId) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getName()).thenReturn("审批");
        when(task.getTaskDefinitionKey()).thenReturn("approve");
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getProcessDefinitionId()).thenReturn(processDefinitionId);
        when(task.getCreateTime()).thenReturn(new Date());
        return task;
    }

    private HistoricTaskInstance historicTask(String taskId, String processDefinitionId) {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getName()).thenReturn("审批");
        when(task.getTaskDefinitionKey()).thenReturn("approve");
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getProcessDefinitionId()).thenReturn(processDefinitionId);
        when(task.getCreateTime()).thenReturn(new Date());
        when(task.getEndTime()).thenReturn(new Date());
        return task;
    }

    private void freezeNodeConfig(String processDefinitionId, String nodeId, String json) {
        BpmnModel model = mock(BpmnModel.class);
        FlowElement element = mock(FlowElement.class);
        when(model.getFlowElement(nodeId)).thenReturn(element);
        when(element.getAttributeValue(FLOWABLE_NS, "nodeConfig")).thenReturn(json);
        when(repositoryService.getBpmnModel(processDefinitionId)).thenReturn(model);
    }
}
