package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.node.BpmNodeDefinition;
import org.flowable.bpmn.model.FlowElement;

/**
 * 节点类型翻译器 SPI —— 把一种流程画布节点类型翻译为 Flowable BPMN 元素。
 * <p>
 * {@link GraphToBpmnTranslator} 按 {@link #type()} 从注册表 Map 分发（仿
 * {@code approverResolverMap} 的 {@code Map<String, ...>} 分发先例），
 * 替代 switch 硬编码。新增节点类型 = 实现本接口并注册，翻译器零改动。
 * </p>
 * <p>
 * <strong>接口落点裁定（M04-F08-01）</strong>：本接口定义在 {@code sw-bpm-engine}
 * 内部而非 {@code sw-bpm-api}——签名必须引用 Flowable 类型
 * （{@link FlowElement} 返回值），不满足 sw-bpm-api「签名零 Flowable」
 * 纯净红线（见 {@code NodeApproverResolver} 先例注释），故落点限定在引擎内部，
 * 不污染 sw-bpm-api。
 * </p>
 * <p>契约：{@link #translate(GraphElement)} 必须返回翻译产物；返回 null
 * 将按缺少节点能力拒绝发布，避免生成缺节点的 BPMN。</p>
 */
public interface NodeTypeTranslator extends BpmNodeDefinition {

    /**
     * 本翻译器处理的节点类型（作为注册表分发 key，如 {@code START}）。
     */
    @Override
    String type();

    /**
     * 将节点翻译为 Flowable BPMN 元素。
     *
     * @param node 画布节点（type 已由注册表按 {@link #type()} 匹配）
     * @return 非空 BPMN 元素；返回 null 将按缺少节点能力拒绝发布
     */
    FlowElement translate(GraphElement node);
}
