package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.FlowElement;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;

import static com.sw.ck.bpm.api.node.BpmNodeCapability.CONFIG_VALIDATE;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.DESIGN;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.RUNTIME;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.TRANSLATE;

/**
 * END 节点翻译器 —— 画布 END 节点 → BPMN {@link EndEvent}。
 * <p>
 * 自 B2（M04-F08-01）起从 {@link GraphToBpmnTranslator} 的 switch 中拆出，
 * 经 {@link NodeTypeTranslator} 注册表分发，翻译行为与拆分前逐字节一致。
 * </p>
 */
@Component
public class EndEventTranslator implements NodeTypeTranslator {

    @Override
    public String type() {
        return "END";
    }

    @Override
    public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata(
                "结束",
                "流程出口节点",
                "EVENT",
                new BpmNodeTopology(1, Integer.MAX_VALUE, 0, 0),
                List.<BpmNodeConfigField>of(),
                "1",
                EnumSet.of(DESIGN, TRANSLATE, RUNTIME, CONFIG_VALIDATE),
                false,
                true,
                true,
                false);
    }

    @Override
    public FlowElement translate(GraphElement node) {
        EndEvent endEvent = new EndEvent();
        endEvent.setId(node.getId());
        endEvent.setName("End");
        return endEvent;
    }
}
