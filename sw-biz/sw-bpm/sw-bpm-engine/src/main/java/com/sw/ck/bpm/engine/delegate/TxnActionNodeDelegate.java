package com.sw.ck.bpm.engine.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.port.FormTxnActionPort;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 事务动作节点委托（P62 分级执行）：经 {@link FormTxnActionPort} 调用受控事务动作，
 * 复用首阶段幂等（节点稳定幂等键 = {@code NODE:{processInstanceId}:{activityId}}，
 * 重试/恢复不产生第二次业务效果）。
 * <p>
 * 失败语义：默认 BLOCK（抛业务异常使该自动步骤失败并保留已完成效果，供流程侧按
 * 部分完成口径查询）；配置 CONTINUE 时记录并推进。动作被业务拒绝（REJECTED）时
 * 结果与错误码写入流程变量，供后续条件节点/回查使用。
 * </p>
 */
@Component("txnActionNodeDelegate")
public class TxnActionNodeDelegate extends NodeDelegateSupport implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(TxnActionNodeDelegate.class);

    private final org.springframework.beans.factory.ObjectProvider<FormTxnActionPort> txnActionPortProvider;

    public TxnActionNodeDelegate(RepositoryService repositoryService, ObjectMapper objectMapper,
                                 ParticipantResolverRegistry participantResolverRegistry,
                                 org.springframework.beans.factory.ObjectProvider<FormTxnActionPort> txnActionPortProvider) {
        super(repositoryService, objectMapper, participantResolverRegistry);
        this.txnActionPortProvider = txnActionPortProvider;
    }

    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Object> config = nodeConfig(execution);
        String actionId = asString(config.get("actionId"));
        if (actionId == null || actionId.isBlank()) {
            throw new BaseException(BpmErrorCode.NODE_CONFIG_INVALID.getCode(), "动作节点必须绑定动作（actionId）");
        }
        String recordId = resolveRecordId(execution, config);
        if (recordId == null || recordId.isBlank()) {
            throw new BaseException(BpmErrorCode.NODE_CONFIG_INVALID.getCode(),
                    "动作节点无法确定目标记录（instanceBusinessKey/variable）");
        }
        String quantity = resolveText(execution, config.get("quantity"));
        String invocationKey = "NODE:" + execution.getProcessInstanceId() + ":" + execution.getCurrentActivityId();

        FormTxnActionPort txnActionPort = txnActionPortProvider.getIfAvailable();
        if (txnActionPort == null) {
            throw new BaseException(BpmErrorCode.NODE_DELIVERY_FAILED.getCode(),
                    "表单事务动作模块未装配，动作节点无法执行");
        }
        FormTxnActionPort.TxnActionResult result = txnActionPort.invoke(new FormTxnActionPort.TxnActionCommand(
                actionId, recordId, quantity, invocationKey, null, null));

        execution.setVariable(actionVar(execution, "status"), result.status());
        execution.setVariable(actionVar(execution, "reservationId"), result.reservationId());
        execution.setVariable(actionVar(execution, "actionVersion"), result.actionVersion());
        if ("SUCCEEDED".equals(result.status())) {
            log.info("动作节点执行成功: processInstance={}, activity={}, actionId={}, reservationId={}, replay={}",
                    execution.getProcessInstanceId(), execution.getCurrentActivityId(), actionId,
                    result.reservationId(), result.replay());
            return;
        }
        String reason = "动作节点被拒绝: " + result.errorCode() + " " + result.errorMsg();
        if (shouldBlock(config)) {
            throw new BaseException(BpmErrorCode.NODE_DELIVERY_FAILED.getCode(), reason);
        }
        execution.setVariable(actionVar(execution, "error"), reason);
        log.warn("动作节点按 CONTINUE 策略跳过失败: processInstance={}, activity={}, reason={}",
                execution.getProcessInstanceId(), execution.getCurrentActivityId(), reason);
    }

    private String resolveRecordId(DelegateExecution execution, Map<String, Object> config) {
        String source = asString(config.get("recordIdSource"));
        if ("variable".equalsIgnoreCase(source)) {
            return resolveText(execution, config.get("recordIdVariable"));
        }
        return execution.getProcessInstanceBusinessKey();
    }

    /** 取值：变量引用（variable:名称 或 ${名称} 或裸变量名存在时）或字面值。 */
    private String resolveText(DelegateExecution execution, Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        String name = null;
        if (text.startsWith("${") && text.endsWith("}")) {
            name = text.substring(2, text.length() - 1).trim();
        } else if (text.startsWith("variable:")) {
            name = text.substring("variable:".length()).trim();
        }
        if (name != null) {
            Object resolved = execution.getVariable(name);
            return resolved == null ? null : String.valueOf(resolved);
        }
        return text;
    }

    private String actionVar(DelegateExecution execution, String field) {
        return "txnAction." + execution.getCurrentActivityId() + "." + field;
    }
}
