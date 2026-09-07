package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** 普通排他条件分支；边条件由 GraphToBpmnTranslator 统一写入。 */
@Component
public class ConditionGatewayTranslator implements NodeTypeTranslator {
    @Override public String type() { return "CONDITION"; }
    @Override public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata("条件分支", "按受控条件选择一条出口", "GATEWAY",
                new BpmNodeTopology(1, 1, 2, Integer.MAX_VALUE),
                List.of(new BpmNodeConfigField("name", "节点名称", "string", false, Map.of())),
                "1", EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                        BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false, false, false, true);
    }
    @Override public FlowElement translate(GraphElement node) {
        ExclusiveGateway gateway = new ExclusiveGateway();
        gateway.setId(node.getId());
        gateway.setName(node.getConfig() != null && node.getConfig().get("name") != null
                ? String.valueOf(node.getConfig().get("name")) : "条件分支");
        return gateway;
    }
}
