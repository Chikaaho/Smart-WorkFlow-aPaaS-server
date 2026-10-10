package com.sw.ck.bpm.engine.translator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.ReceiveTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sw.ck.bpm.api.node.BpmNodeCapability.CONFIG_VALIDATE;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.DESIGN;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.RUNTIME;
import static com.sw.ck.bpm.api.node.BpmNodeCapability.TRANSLATE;

/**
 * SUBFLOW_WAIT 节点翻译器（P64 阶段Ⅱ A05 主子流程等待）—— 画布 SUBFLOW_WAIT 节点 →
 * BPMN {@link ReceiveTask}。
 * <p>
 * 父流程在派发子流程后经本节点挂起，子流程批次按等待策略（ALL/ANY/COUNT）结算后由
 * 编排域经 {@code BpmRuntimeFacade#signalWaitNode} 唤醒推进主链；NONE 策略动作的批次
 * 派发即结算，配置引用 NONE 动作发布拒绝（无等待语义可等）。
 * 到达事件经 {@code SubflowWaitListener}（delegation expression → Spring bean）回调
 * 编排域端口：令牌到达时引用批次已全部结算则立即唤醒，否则挂起等待。
 * </p>
 */
@Component
public class SubflowWaitNodeTranslator implements NodeTypeTranslator {

    /** 执行监听 Spring bean 名（delegation expression）。 */
    public static final String WAIT_LISTENER_BEAN = "subflowWaitListener";

    @Override
    public Optional<String> type() {
        return Optional.of("SUBFLOW_WAIT");
    }

    @Override
    public Optional<BpmNodeMetadata> metadata() {
        return Optional.of(new BpmNodeMetadata(
                "子流程等待",
                "挂起父流程，等待引用的子流程批次按等待策略结算后继续（P64 主子流程）",
                "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(
                        new BpmNodeConfigField("name", "节点名称", "string", false, Map.of()),
                        new BpmNodeConfigField("waitActionIds", "等待的子流程动作 ID 集合", "array",
                                false, Map.of("shape", "[actionId,...]；缺省=本图全部 CHILD 动作"))),
                "1",
                EnumSet.of(DESIGN, TRANSLATE, RUNTIME, CONFIG_VALIDATE),
                false,
                false,
                false,
                true));
    }

    @Override
    public Optional<List<GraphValidationError>> validateConfig(GraphElement node) {
        List<GraphValidationError> errors = new ArrayList<>();
        Map<String, Object> config = node.getConfig();
        if (config != null && config.get("waitActionIds") != null
                && !(config.get("waitActionIds") instanceof List<?>)) {
            errors.add(GraphValidationError.builder()
                    .elementId(node.getId())
                    .errorCode(BpmErrorCode.WAIT_NODE_CONFIG_INVALID.getCode())
                    .message("子流程等待节点 waitActionIds 必须是动作 ID 数组")
                    .build());
        }
        return Optional.of(errors);
    }

    @Override
    public FlowElement translate(GraphElement node) {
        ReceiveTask receiveTask = new ReceiveTask();
        receiveTask.setId(node.getId());
        Map<String, Object> config = node.getConfig();
        Object name = config == null ? null : config.get("name");
        receiveTask.setName(name == null || String.valueOf(name).isBlank()
                ? "子流程等待" : String.valueOf(name));

        // 挂载 start 事件执行监听（delegation expression → Spring bean）：
        // 令牌到达时回调编排域端口，引用批次已全部结算则立即唤醒，否则挂起
        FlowableListener listener = new FlowableListener();
        listener.setEvent("start");
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation("${" + WAIT_LISTENER_BEAN + "}");
        receiveTask.getExecutionListeners().add(listener);
        return receiveTask;
    }
}
