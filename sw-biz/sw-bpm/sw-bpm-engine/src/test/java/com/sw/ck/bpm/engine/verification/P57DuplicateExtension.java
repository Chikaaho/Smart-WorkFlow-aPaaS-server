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

/** 仅用于真实 Spring 启动失败复现：与内置 START 类型重复。 */
@Profile("p57-invalid-duplicate")
@Component
public class P57DuplicateExtension implements NodeTypeTranslator {
    @Override public String type() { return "START"; }
    @Override public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata("重复", "重复节点", "EVENT",
                new BpmNodeTopology(0, 0, 1, 1), List.of(), "invalid",
                EnumSet.allOf(BpmNodeCapability.class), true, false, true, false);
    }
    @Override public FlowElement translate(GraphElement node) { return null; }
}
