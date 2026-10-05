package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ParallelGateway;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 手工并行网关（P63 §2/A09）：多出口并行分叉、多入口到齐汇合（Flowable AND 语义）。
 * <p>
 * 图契约增量新增节点类型，经统一注册表装配，不改变既有图模型与旧图语义；
 * 边为无条件顺序流（条件边仍归条件分支）。1 入多出=分叉，多入 1 出=汇合。
 * 手工并行分支体内可放置审批/会签/动态并行等多节点，每条路径独立推进，
 * 汇合网关等全部活跃分支到齐后继续（与动态汇聚边界互不影响）。
 * </p>
 */
@Component
public class ParallelGatewayTranslator implements NodeTypeTranslator {
    @Override
    public Optional<String> type() {
        return Optional.of("PARALLEL_GATEWAY");
    }

    @Override
    public Optional<BpmNodeMetadata> metadata() {
        return Optional.of(new BpmNodeMetadata("并行网关", "多路径并行分支与汇合（全部到齐后继续）", "GATEWAY",
                new BpmNodeTopology(1, Integer.MAX_VALUE, 1, Integer.MAX_VALUE),
                List.of(new BpmNodeConfigField("name", "节点名称", "string", false, Map.of())),
                "1", EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                        BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false, false, false, true));
    }

    @Override
    public FlowElement translate(GraphElement node) {
        ParallelGateway gateway = new ParallelGateway();
        gateway.setId(node.getId());
        gateway.setName(node.getConfig() != null && node.getConfig().get("name") != null
                ? String.valueOf(node.getConfig().get("name")) : "并行网关");
        return gateway;
    }
}
