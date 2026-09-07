package com.sw.ck.bpm.engine.verification;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.bpm.engine.translator.NodeTypeTranslator;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.ServiceTask;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * P57 真实应用隔离验证节点。
 *
 * 仅在显式启用 p57-evidence profile 时加入 Spring 应用上下文；普通 dev/prod
 * 不会发现该节点，因此不会污染正式节点目录或正式业务能力。
 */
@Profile("p57-evidence")
@Component
public class P57IsolatedVerificationNode implements NodeTypeTranslator {

    public static final String TYPE = "P57_VERIFY";
    public static final String DELEGATE_EXPRESSION = "${p57VerificationNodeDelegate}";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata(
                "隔离验证节点",
                "P57 真实应用隔离验证夹具",
                "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(new BpmNodeConfigField(
                        "message", "验证消息", "string", true, Map.of("minLength", 1))),
                "p57-evidence-v1",
                EnumSet.allOf(BpmNodeCapability.class),
                false,
                false,
                true,
                false);
    }

    @Override
    public List<GraphValidationError> validateConfig(GraphElement node) {
        Object message = node.getConfig() == null ? null : node.getConfig().get("message");
        if (message == null || message.toString().isBlank()) {
            return List.of(GraphValidationError.builder()
                    .elementId(node.getId())
                    .errorCode(2106)
                    .message("隔离验证节点缺少 message 配置")
                    .build());
        }
        return List.of();
    }

    @Override
    public FlowElement translate(GraphElement node) {
        ServiceTask serviceTask = new ServiceTask();
        serviceTask.setId(node.getId());
        serviceTask.setName("隔离验证节点");
        serviceTask.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        serviceTask.setImplementation(DELEGATE_EXPRESSION);
        return serviceTask;
    }
}
