package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.form.api.port.FormTxnActionPort;
import org.flowable.bpmn.model.FlowElement;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事务动作节点（P62 分级执行 · 生产轻流程）：经 form-api 受控 Port 调用已发布事务动作。
 * <p>
 * 限定用途：START/END/CONDITION/动作的短流程自动步骤，不承载人工等待；
 * 节点配置只声明绑定动作与取值来源，动作内核（冻结版本/幂等指纹/预占台账/C1）
 * 全部复用首阶段交付实现。发布期即校验绑定动作存在、同租户且已发布
 *（{@link FormTxnActionPort#describe}，跨租户/缺失按不存在处理）。
 * </p>
 */
@Component
public class TxnActionNodeTranslator extends ServiceTaskNodeTranslator {

    private final org.springframework.beans.factory.ObjectProvider<FormTxnActionPort> txnActionPortProvider;

    public TxnActionNodeTranslator(ObjectMapper objectMapper,
                                   org.springframework.beans.factory.ObjectProvider<FormTxnActionPort> txnActionPortProvider) {
        super(objectMapper);
        this.txnActionPortProvider = txnActionPortProvider;
    }

    @Override
    public Optional<String> type() {
        return Optional.of("TXN_ACTION");
    }

    @Override
    protected String delegateBean() {
        return "txnActionNodeDelegate";
    }

    @Override
    protected String nodeName() {
        return "事务动作";
    }

    @Override
    public Optional<BpmNodeMetadata> metadata() {
        return Optional.of(new BpmNodeMetadata(nodeName(), nodeName() + "节点", "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(new BpmNodeConfigField("name", "节点名称", "string", false, Map.of()),
                        new BpmNodeConfigField("actionId", "绑定事务动作", "string", true, Map.of()),
                        new BpmNodeConfigField("recordIdSource", "记录来源", "string", false,
                                Map.of("values", List.of("instanceBusinessKey", "variable"))),
                        new BpmNodeConfigField("quantity", "数量", "string", false, Map.of()),
                        new BpmNodeConfigField("failureStrategy", "失败策略", "string", false,
                                Map.of("values", List.of("BLOCK", "CONTINUE")))),
                "1", EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                        BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false, false, false, true));
    }

    /**
     * 事务动作节点按独立短事务执行（方向 U01 合同：各节点独立短事务，节点效果、
     * 进度和结果同事务；先前已提交节点不因后续失败自动撤销）。async 使每个节点
     * 拥有独立 Flowable 事务：效果（REQUIRES_NEW 预占）与变量写回同事务提交，
     * 后续节点失败只回滚自身，已提交节点保留。
     */
    @Override
    public FlowElement translate(GraphElement node) {
        FlowElement element = super.translate(node);
        if (element instanceof org.flowable.bpmn.model.ServiceTask task) {
            task.setAsynchronous(true);
        }
        return element;
    }

    @Override
    public Optional<List<GraphValidationError>> validateConfig(GraphElement node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String actionId = text(config.get("actionId"));
        if (actionId == null) {
            return Optional.of(List.of(error(node, "动作节点必须绑定动作（actionId）")));
        }
        String strategy = text(config.get("failureStrategy"));
        if (strategy != null && !List.of("BLOCK", "CONTINUE").contains(strategy.toUpperCase())) {
            return Optional.of(List.of(error(node, "失败策略不合法: " + strategy)));
        }
        FormTxnActionPort txnActionPort = txnActionPortProvider.getIfAvailable();
        if (txnActionPort == null) {
            // 隔离引擎上下文未装配表单模块：动作节点 fail closed（无动作节点的图不受影响）
            return Optional.of(List.of(error(node, "表单事务动作模块未装配，动作节点不可用")));
        }
        Optional<FormTxnActionPort.TxnActionDescriptor> descriptor = txnActionPort.describe(actionId);
        if (descriptor.isEmpty()) {
            return Optional.of(List.of(error(node, "绑定动作不存在或跨租户不可达: " + actionId)));
        }
        if (!"PUBLISHED".equals(descriptor.get().status())) {
            return Optional.of(List.of(error(node,
                    "绑定动作未发布（当前状态 " + descriptor.get().status() + "），发布前请先发布该动作")));
        }
        return Optional.of(List.of());
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private GraphValidationError error(GraphElement node, String message) {
        return GraphValidationError.builder().elementId(node.getId())
                .errorCode(BpmErrorCode.NODE_CONFIG_INVALID.getCode()).message(message).build();
    }
}
