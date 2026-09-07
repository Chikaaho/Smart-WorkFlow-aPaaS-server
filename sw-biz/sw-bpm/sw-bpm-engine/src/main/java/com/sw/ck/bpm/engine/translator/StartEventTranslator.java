package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.StartEvent;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;

import static com.sw.ck.bpm.api.node.BpmNodeCapability.CONFIG_VALIDATE;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.DESIGN;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.RUNTIME;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.TRANSLATE;

/**
 * START 节点翻译器 —— 画布 START 节点 → BPMN {@link StartEvent}。
 * <p>
 * 自 B2（M04-F08-01）起从 {@link GraphToBpmnTranslator} 的 switch 中拆出，
 * 经 {@link NodeTypeTranslator} 注册表分发，翻译行为与拆分前逐字节一致。
 * </p>
 */
@Component
public class StartEventTranslator implements NodeTypeTranslator {

    @Override
    public String type() {
        return "START";
    }

    @Override
    public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata(
                "开始",
                "流程入口节点",
                "EVENT",
                new BpmNodeTopology(0, 0, 1, 1),
                List.<BpmNodeConfigField>of(),
                "1",
                EnumSet.of(DESIGN, TRANSLATE, RUNTIME, CONFIG_VALIDATE),
                true,
                false,
                true,
                false);
    }

    @Override
    public FlowElement translate(GraphElement node) {
        StartEvent startEvent = new StartEvent();
        startEvent.setId(node.getId());
        startEvent.setName("Start");
        return startEvent;
    }
}
