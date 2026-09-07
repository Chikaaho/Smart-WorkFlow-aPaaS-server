package com.sw.ck.bpm.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeDefinition;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.engine.translator.NodeTypeTranslator;
import com.sw.ck.bpm.engine.translator.ApprovalUserTaskTranslator;
import com.sw.ck.bpm.engine.translator.EndEventTranslator;
import com.sw.ck.bpm.engine.translator.StartEventTranslator;
import org.flowable.bpmn.model.FlowElement;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmNodeRegistryImplTest {

    @Test
    void ordersDefinitionsByStableTypeAndExposesOnlyRegisteredCapabilities() {
        BpmNodeRegistryImpl registry = new BpmNodeRegistryImpl(List.of(
                definition("END"), definition("APPROVAL"), definition("START")));

        assertThat(registry.definitions()).extracting(BpmNodeDefinition::type)
                .containsExactly("APPROVAL", "END", "START");
        assertThat(registry.capabilities()).extracting(capability -> capability.type())
                .containsExactly("APPROVAL", "END", "START");
    }

    @Test
    void rejectsDuplicateStableType() {
        assertThatThrownBy(() -> new BpmNodeRegistryImpl(List.of(
                definition("CUSTOM"), definition("CUSTOM"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("节点类型重复");
    }

    @Test
    void rejectsInvalidStableType() {
        assertThatThrownBy(() -> new BpmNodeRegistryImpl(List.of(definition("custom"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("节点类型标识非法");
    }

    @Test
    void serializesTheRegisteredContractForFrontendConsumption() throws Exception {
        BpmNodeRegistryImpl registry = new BpmNodeRegistryImpl(List.of(
                new EndEventTranslator(),
                new ApprovalUserTaskTranslator(new ObjectMapper()),
                new StartEventTranslator()));

        var json = new ObjectMapper().valueToTree(registry.capabilities());

        assertThat(json.isArray()).isTrue();
        assertThat(json.get(0).get("type").asText()).isEqualTo("APPROVAL");
        assertThat(json.get(0).get("topology").get("minIncoming").asInt()).isEqualTo(1);
        assertThat(json.get(0).get("configFields").get(1).get("key").asText())
                .isEqualTo("approver");
        assertThat(json.get(0).get("supports").get("run").asBoolean()).isTrue();
        assertThat(json.get(1).get("topology").get("maxIncoming").asInt())
                .isEqualTo(Integer.MAX_VALUE);
    }

    private static BpmNodeDefinition definition(String type) {
        return new NodeTypeTranslator() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public BpmNodeMetadata metadata() {
                return new BpmNodeMetadata(
                        type,
                        "测试节点",
                        "OTHER",
                        new BpmNodeTopology(0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE),
                        List.of(),
                        "test-v1",
                        EnumSet.allOf(BpmNodeCapability.class),
                        false,
                        false,
                        false,
                        true);
            }

            @Override
            public FlowElement translate(GraphElement node) {
                return null;
            }
        };
    }
}
