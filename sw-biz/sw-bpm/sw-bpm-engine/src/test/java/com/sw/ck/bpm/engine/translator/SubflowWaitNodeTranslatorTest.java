package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUBFLOW_WAIT 等待节点翻译器单测（P64 阶段Ⅱ A05）：
 * 翻译为 ReceiveTask + start 执行监听（delegation expression）；配置校验单一权威。
 */
@DisplayName("SUBFLOW_WAIT 等待节点翻译器测试")
class SubflowWaitNodeTranslatorTest {

    private final SubflowWaitNodeTranslator translator = new SubflowWaitNodeTranslator();

    @Test
    @DisplayName("翻译为 ReceiveTask 并挂载 start 执行监听（subflowWaitListener）")
    void translatesToReceiveTaskWithStartListener() {
        GraphElement node = GraphElement.builder()
                .id("wait_1")
                .kind("node")
                .type("SUBFLOW_WAIT")
                .config(Map.of("name", "等待子流程",
                        "waitActionIds", List.of("act_a", "act_b")))
                .build();

        FlowElement element = translator.translate(node);

        assertThat(element).isInstanceOf(ReceiveTask.class);
        ReceiveTask receiveTask = (ReceiveTask) element;
        assertThat(receiveTask.getId()).isEqualTo("wait_1");
        assertThat(receiveTask.getName()).isEqualTo("等待子流程");
        assertThat(receiveTask.getExecutionListeners()).hasSize(1);
        assertThat(receiveTask.getExecutionListeners().get(0).getEvent()).isEqualTo("start");
        assertThat(receiveTask.getExecutionListeners().get(0).getImplementation())
                .isEqualTo("${subflowWaitListener}");
    }

    @Test
    @DisplayName("validateConfig：waitActionIds 非数组发布拒绝；合法数组与缺省通过")
    void validatesConfigShape() {
        assertThat(translator.validateConfig(GraphElement.builder()
                .id("w").kind("node").type("SUBFLOW_WAIT")
                .config(Map.of("waitActionIds", "not-a-list"))
                .build()))
                .isPresent()
                .hasValueSatisfying(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getErrorCode())
                            .isEqualTo(BpmErrorCode.WAIT_NODE_CONFIG_INVALID.getCode());
                });
        assertThat(translator.validateConfig(GraphElement.builder()
                .id("w").kind("node").type("SUBFLOW_WAIT")
                .config(Map.of("waitActionIds", List.of("act_a")))
                .build()))
                .hasValueSatisfying(errors -> assertThat(errors).isEmpty());
        assertThat(translator.validateConfig(GraphElement.builder()
                .id("w").kind("node").type("SUBFLOW_WAIT")
                .build()))
                .hasValueSatisfying(errors -> assertThat(errors).isEmpty());
    }

    @Test
    @DisplayName("注册元数据：类型稳定、拓扑 1 进 1 出、无参与人配置")
    void metadataRegistered() {
        assertThat(translator.type()).isEqualTo(Optional.of("SUBFLOW_WAIT"));
        assertThat(translator.metadata()).isPresent();
        assertThat(translator.metadata().orElseThrow().topology().minIncoming()).isEqualTo(1);
        assertThat(translator.metadata().orElseThrow().topology().maxOutgoing()).isEqualTo(1);
    }
}
