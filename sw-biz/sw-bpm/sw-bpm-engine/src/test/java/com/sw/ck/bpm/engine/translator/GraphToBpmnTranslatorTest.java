package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.common.exception.BaseException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.bpmn.model.SequenceFlow;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GraphToBpmnTranslator 纯函数单元测试。
 * <p>
 * 断言给定 {@link ProcessGraph} → {@link BpmnModel} 的结构正确性，
 * 不需启动 Flowable 引擎。
 * </p>
 */
class GraphToBpmnTranslatorTest {

    private final GraphToBpmnTranslator translator = new GraphToBpmnTranslator();

    @Test
    void shouldTranslateStartApprovalEndGraph() {
        // 给定：START → node_approval → END
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("test_process")
                .name("Test Process")
                .elements(List.of(
                        node("start_id", "START"),
                        node("approval_id", "APPROVAL", approverConfig("DESIGNATED", List.of("user1"))),
                        node("end_id", "END"),
                        edge("e1", "start_id", "approval_id"),
                        edge("e2", "approval_id", "end_id")
                ))
                .build();

        BpmnModel model = translator.translate(graph);

        // BpmnModel 含一个 Process
        assertThat(model.getProcesses()).hasSize(1);
        org.flowable.bpmn.model.Process process = model.getProcesses().get(0);
        assertThat(process.getId()).isEqualTo("test_process");
        assertThat(process.getName()).isEqualTo("Test Process");
        assertThat(process.isExecutable()).isTrue();

        // 应含 StartEvent / UserTask / EndEvent / 2个 SequenceFlow = 5 个 FlowElement
        assertThat(process.getFlowElements()).hasSize(5);

        // StartEvent
        StartEvent startEvent = (StartEvent) process.getFlowElement("start_id");
        assertThat(startEvent).isNotNull();
        assertThat(startEvent.getId()).isEqualTo("start_id");

        // EndEvent
        EndEvent endEvent = (EndEvent) process.getFlowElement("end_id");
        assertThat(endEvent).isNotNull();
        assertThat(endEvent.getId()).isEqualTo("end_id");

        // UserTask
        UserTask userTask = (UserTask) process.getFlowElement("approval_id");
        assertThat(userTask).isNotNull();
        assertThat(userTask.getId()).isEqualTo("approval_id");
        assertThat(userTask.getName()).isEqualTo("审批");

        // DESIGNATED 指定审批人直接写原生 assignee（引擎插入时持久化，历史表可查）
        assertThat(userTask.getAssignee()).isEqualTo("user1");

        // SequenceFlows
        SequenceFlow flow1 = (SequenceFlow) process.getFlowElement("e1");
        assertThat(flow1).isNotNull();
        assertThat(flow1.getSourceRef()).isEqualTo("start_id");
        assertThat(flow1.getTargetRef()).isEqualTo("approval_id");

        SequenceFlow flow2 = (SequenceFlow) process.getFlowElement("e2");
        assertThat(flow2).isNotNull();
        assertThat(flow2.getSourceRef()).isEqualTo("approval_id");
        assertThat(flow2.getTargetRef()).isEqualTo("end_id");
    }

    @Test
    void userTaskShouldCarryTaskListener() {
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("test_flow")
                .elements(List.of(
                        node("start", "START"),
                        node("node1", "APPROVAL", approverConfig("DESIGNATED", List.of("u1"))),
                        node("end", "END"),
                        edge("e1", "start", "node1"),
                        edge("e2", "node1", "end")
                ))
                .build();

        BpmnModel model = translator.translate(graph);
        UserTask userTask = (UserTask) model.getProcesses().get(0).getFlowElement("node1");
        assertThat(userTask).isNotNull();

        // 应挂载 create 事件 TaskListener
        List<FlowableListener> listeners = userTask.getTaskListeners();
        assertThat(listeners).isNotEmpty();

        FlowableListener listener = listeners.get(0);
        assertThat(listener.getEvent()).isEqualTo("create");
        // delegation expression → Flowable 经 Spring 表达式管理器解析 bean
        assertThat(listener.getImplementationType())
                .isEqualTo(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        assertThat(listener.getImplementation())
                .isEqualTo("${approvalTaskListener}");
    }

    @Test
    void userTaskShouldCarryApproverConfigAttribute() {
        Map<String, Object> approverCfg = Map.of("approver", Map.of("type", "DESIGNATED", "value", List.of(1L, 2L)));
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("cfg_test")
                .elements(List.of(
                        node("start", "START"),
                        node("node1", "APPROVAL", approverCfg),
                        node("end", "END"),
                        edge("e1", "start", "node1"),
                        edge("e2", "node1", "end")
                ))
                .build();

        BpmnModel model = translator.translate(graph);
        UserTask userTask = (UserTask) model.getProcesses().get(0).getFlowElement("node1");

        String approverConfig = userTask.getAttributeValue("http://flowable.org/bpmn", "approverConfig");
        assertThat(approverConfig).isNotNull().isNotBlank();
        // 应包含 type 和 value
        assertThat(approverConfig).contains("DESIGNATED");
        assertThat(approverConfig).contains("1");
    }

    @Test
    void shouldRejectNullGraph() {
        assertThrows(NullPointerException.class, () -> translator.translate(null));
    }

    @Test
    void shouldRejectEmptyElements() {
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("empty")
                .elements(Collections.emptyList())
                .build();
        assertThrows(BaseException.class, () -> translator.translate(graph));
    }

    @Test
    void translatorShouldBeDeterministic() {
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("deterministic")
                .elements(List.of(
                        node("s", "START"),
                        node("a", "APPROVAL", approverConfig("DESIGNATED", List.of("u1"))),
                        node("e", "END"),
                        edge("e1", "s", "a"),
                        edge("e2", "a", "e")
                ))
                .build();

        BpmnModel model1 = translator.translate(graph);
        BpmnModel model2 = translator.translate(graph);

        // 同一输入 → 相同 processKey 和结构
        assertThat(model1.getProcesses().get(0).getId())
                .isEqualTo(model2.getProcesses().get(0).getId());
        assertThat(model1.getProcesses().get(0).getFlowElements().size())
                .isEqualTo(model2.getProcesses().get(0).getFlowElements().size());
    }

    @Test
    void shouldRejectUnknownNodeType() {
        // 发布翻译不能跳过未注册节点，否则会生成缺少节点/悬空边的 BPMN。
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("unknown_type")
                .elements(List.of(
                        node("start", "START"),
                        node("cond", "CONDITION"),
                        node("approval", "APPROVAL", approverConfig("DESIGNATED", List.of("u1"))),
                        node("end", "END"),
                        edge("e1", "start", "cond"),
                        edge("e2", "cond", "approval"),
                        edge("e3", "approval", "end")
                ))
                .build();

        BaseException exception = assertThrows(BaseException.class, () -> translator.translate(graph));
        assertThat(exception.getCode()).isEqualTo(2107);
    }

    // ==================== helpers ====================

    private static GraphElement node(String id, String type) {
        return GraphElement.builder()
                .id(id)
                .kind("node")
                .type(type)
                .config(Collections.emptyMap())
                .style(Collections.emptyMap())
                .build();
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id)
                .kind("node")
                .type(type)
                .config(config)
                .style(Collections.emptyMap())
                .build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder()
                .id(id)
                .kind("edge")
                .source(source)
                .target(target)
                .config(Collections.emptyMap())
                .style(Collections.emptyMap())
                .build();
    }

    private static Map<String, Object> approverConfig(String type, List<String> userIds) {
        return Map.of("approver", Map.of("type", type, "value", userIds));
    }
}
