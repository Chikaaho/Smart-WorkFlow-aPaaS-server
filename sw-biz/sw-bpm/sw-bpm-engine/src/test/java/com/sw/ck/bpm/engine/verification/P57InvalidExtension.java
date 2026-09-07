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

/** 仅用于真实 Spring 启动失败复现：非法类别。 */
@Profile("p57-invalid-illegal")
@Component
public class P57InvalidExtension implements NodeTypeTranslator {
    @Override public String type() { return "P57_INVALID"; }
    @Override public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata("非法", "非法节点", "NOT_A_CATEGORY",
                new BpmNodeTopology(0, 1, 0, 1), List.of(), "invalid",
                EnumSet.allOf(BpmNodeCapability.class), false, false, true, false);
    }
    @Override public FlowElement translate(GraphElement node) { return null; }
}
