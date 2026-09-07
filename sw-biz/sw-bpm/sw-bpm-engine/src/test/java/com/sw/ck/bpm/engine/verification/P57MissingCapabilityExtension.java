package com.sw.ck.bpm.engine.verification;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.bpm.engine.translator.NodeTypeTranslator;
import org.flowable.bpmn.model.FlowElement;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;

/** 仅用于真实 Spring 启动失败复现：缺少 RUNTIME/CONFIG_VALIDATE 能力。 */
@Profile("p57-invalid-missing-capability")
@Component
public class P57MissingCapabilityExtension implements NodeTypeTranslator {
    @Override public String type() { return "P57_MISSING"; }
    @Override public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata("缺能力", "缺能力节点", "TASK",
                new BpmNodeTopology(0, 1, 0, 1), List.of(), "invalid",
                EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE),
                false, false, true, false);
    }
    @Override public FlowElement translate(GraphElement node) { return null; }
}
