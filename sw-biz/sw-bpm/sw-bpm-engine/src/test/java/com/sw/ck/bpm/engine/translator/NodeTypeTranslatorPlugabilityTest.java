package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.common.exception.BaseException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M04-F08-01 可插拔性证明 —— 注册新节点类型翻译器后，消费方（{@link GraphToBpmnTranslator}）
 * 零改动即翻译该类型（方向文档验收标准 2 的后端测试证据）。
 * <p>
 * 证明方式：本测试向翻译器注册一个测试型节点翻译器
 * {@code TEST_NODE → ServiceTask}（不新增任何生产节点类型），
 * 断言 {@code translate()} 无需任何改动即产出对应 Flowable 元素。
 * </p>
 */
class NodeTypeTranslatorPlugabilityTest {

    /** 测试型节点翻译器：TEST_NODE → ServiceTask（可验证的 Flowable 元素）。 */
    private static final NodeTypeTranslator TEST_NODE_TRANSLATOR = new NodeTypeTranslator() {
        @Override
        public String type() {
            return "TEST_NODE";
        }

        @Override
        public FlowElement translate(GraphElement node) {
            ServiceTask serviceTask = new ServiceTask();
            serviceTask.setId(node.getId());
            serviceTask.setName("TestService");
            return serviceTask;
        }
    };

    @Test
    void shouldTranslatePluggedNodeTypeWithoutConsumerChange() {
        // 插件注册（新增类型仅注册即可）
        GraphToBpmnTranslator translator = new GraphToBpmnTranslator(
                new ObjectMapper(), List.of(TEST_NODE_TRANSLATOR));

        // 混合图：START → TEST_NODE → APPROVAL → END
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("plugin_test")
                .elements(List.of(
                        node("start", "START"),
                        node("test", "TEST_NODE"),
                        node("approval", "APPROVAL", approverConfig("DESIGNATED", List.of("u1"))),
                        node("end", "END"),
                        edge("e1", "start", "test"),
                        edge("e2", "test", "approval"),
                        edge("e3", "approval", "end")
                ))
                .build();

        BpmnModel model = translator.translate(graph);
        org.flowable.bpmn.model.Process process = model.getProcesses().get(0);

        // 1. 新类型被翻译：TEST_NODE → ServiceTask
        FlowElement testElement = process.getFlowElement("test");
        assertThat(testElement).isInstanceOf(ServiceTask.class);
        ServiceTask serviceTask = (ServiceTask) testElement;
        assertThat(serviceTask.getId()).isEqualTo("test");
        assertThat(serviceTask.getName()).isEqualTo("TestService");

        // 2. 新类型参与边连接（进出 SequenceFlow 均产出）
        assertThat(process.getFlowElement("e1")).isInstanceOf(SequenceFlow.class);
        assertThat(process.getFlowElement("e2")).isInstanceOf(SequenceFlow.class);

        // 3. 内置翻译器不受插件注册影响：START/APPROVAL/END 照常翻译
        assertThat(process.getFlowElement("start")).isNotNull();
        assertThat(process.getFlowElement("approval")).isInstanceOf(UserTask.class);
        assertThat(process.getFlowElement("end")).isNotNull();
    }

    @Test
    void shouldRejectUnknownTypeWhenPluginsRegistered() {
        GraphToBpmnTranslator translator = new GraphToBpmnTranslator(
                new ObjectMapper(), List.of(TEST_NODE_TRANSLATOR));

        ProcessGraph graph = ProcessGraph.builder()
                .processKey("plugin_unknown")
                .elements(List.of(
                        node("start", "START"),
                        node("cond", "CONDITION"),
                        node("end", "END"),
                        edge("e1", "start", "cond"),
                        edge("e2", "cond", "end")
                ))
                .build();

        // 发布翻译不能再 warn + skip，否则会生成缺少节点/悬空边的 BPMN。
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BaseException.class, () -> translator.translate(graph)).getCode())
                .isEqualTo(2107);
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
