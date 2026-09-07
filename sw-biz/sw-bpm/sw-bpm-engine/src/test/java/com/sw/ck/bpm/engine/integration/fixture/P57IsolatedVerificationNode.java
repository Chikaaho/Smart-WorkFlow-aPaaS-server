package com.sw.ck.bpm.engine.integration.fixture;

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
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * P57 隔离验证节点，仅存在于测试夹具，不进入正式业务节点目录。
 * <p>
 * 该类使用与生产节点相同的 Spring 组件发现边界，验证节点重启/重建后仍可进入统一注册结果，
 * 再由 Flowable 真正执行其 delegate expression。
 * </p>
 */
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
                "隔离验证",
                "P57 隔离节点运行验证夹具",
                "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(new BpmNodeConfigField(
                        "message", "验证消息", "string", true, Map.of("minLength", 1))),
                "p57-test-v1",
                EnumSet.allOf(BpmNodeCapability.class),
                false,
                false,
                false,
                true);
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
        serviceTask.setName("隔离验证");
        serviceTask.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        serviceTask.setImplementation(DELEGATE_EXPRESSION);
        return serviceTask;
    }
}
