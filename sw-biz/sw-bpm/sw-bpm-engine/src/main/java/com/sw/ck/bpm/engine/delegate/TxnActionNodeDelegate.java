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
    private final org.springframework.transaction.support.TransactionTemplate nodeTxTemplate;
    private final org.springframework.context.ApplicationContext applicationContext;
    private final com.sw.ck.security.spi.UserDetailsProvider userDetailsProvider;

    public TxnActionNodeDelegate(RepositoryService repositoryService, ObjectMapper objectMapper,
                                 ParticipantResolverRegistry participantResolverRegistry,
                                 org.springframework.beans.factory.ObjectProvider<FormTxnActionPort> txnActionPortProvider,
                                 org.springframework.transaction.PlatformTransactionManager transactionManager,
                                 org.springframework.context.ApplicationContext applicationContext,
                                 org.springframework.beans.factory.ObjectProvider<com.sw.ck.security.spi.UserDetailsProvider> userDetailsProvider) {
        super(repositoryService, objectMapper, participantResolverRegistry);
        this.txnActionPortProvider = txnActionPortProvider;
        this.applicationContext = applicationContext;
        this.userDetailsProvider = userDetailsProvider == null ? null : userDetailsProvider.getIfAvailable();
        // 生产轻流程各节点独立短事务（方向 U01 合同）：节点效果独立提交，
        // 先前已提交节点不因后续节点失败自动撤销
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nodeTxTemplate = template;
    }

    @Override
    public void execute(DelegateExecution execution) {
        // async 执行线程无登录态：按流程发起人还原上下文（方向 U01：恢复不得以超级用户兜底；
        // 动作租户与权限仍由受控 Port 按还原身份校验，发起人无调用权限则节点拒绝）
        com.sw.ck.security.holder.LoginUser previous = com.sw.ck.security.holder.LoginUserHolder.get();
        boolean restoreNeeded = previous == null;
        if (restoreNeeded) {
            Object submitter = execution.getVariable("submitter");
            Object tenantId = execution.getVariable("tenantId");
            if (submitter == null || tenantId == null) {
                throw new BaseException(BpmErrorCode.NODE_DELIVERY_FAILED.getCode(),
                        "动作节点执行缺少发起人上下文（submitter/tenantId 流程变量缺失）");
            }
            long initiatorId = Long.parseLong(String.valueOf(submitter));
            long initiatorTenant = Long.parseLong(String.valueOf(tenantId));
            com.sw.ck.security.holder.LoginUser owner;
            if (userDetailsProvider != null) {
                // 正式身份回查（含最新 RBAC 权限），不构造无权限的裸身份、不以超级用户兜底
                owner = userDetailsProvider.loadByUserId(initiatorId);
                if (owner == null || !Long.valueOf(initiatorTenant).equals(owner.getTenantId())) {
                    throw new BaseException(BpmErrorCode.INSTANCE_INITIATOR_INVALID);
                }
            } else {
                owner = new com.sw.ck.security.holder.LoginUser();
                owner.setUserId(initiatorId);
                owner.setTenantId(initiatorTenant);
            }
            com.sw.ck.security.holder.LoginUserHolder.set(owner);
        }
        try {
            doExecute(execution);
        } finally {
            if (restoreNeeded) {
                com.sw.ck.security.holder.LoginUserHolder.clear();
            }
        }
    }

    private void doExecute(DelegateExecution execution) {
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
        FormTxnActionPort.TxnActionResult result;
        try {
            // FD02：Port 合同恒返回有值 Optional（失败以异常或结果 status 表达）
            result = nodeTxTemplate.execute(status ->
                    txnActionPort.invoke(new FormTxnActionPort.TxnActionCommand(
                            actionId, recordId, quantity, invocationKey, null, null)))
                    .orElseThrow(() -> new IllegalStateException("动作调用无结果"));
        
        } catch (org.springframework.transaction.UnexpectedRollbackException rollback) {
            // 业务拒绝（REJECTED）在动作内核"另事务"记录后回滚拒绝事务：独立短事务随之回滚
            // 属预期语义（拒绝不产生效果），回查已独立提交的拒绝记录恢复结果供 CONTINUE/BLOCK 分支
            result = loadRejectedResult(invocationKey, rollback);
        }

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
        if (blockOnFailure(config)) {
            throw new BaseException(BpmErrorCode.NODE_DELIVERY_FAILED.getCode(), reason);
        }
        execution.setVariable(actionVar(execution, "error"), reason);
        log.warn("动作节点按 CONTINUE 策略跳过失败: processInstance={}, activity={}, reason={}",
                execution.getProcessInstanceId(), execution.getCurrentActivityId(), reason);
    }

    /**
     * 事务动作节点失败语义（方向 U01/G3a 合同）：默认 BLOCK——动作拒绝时抛业务异常使
     * 该自动步骤失败，已提交节点效果与进度保留、实例不伪报整体成功；显式配置
     * {@code failureStrategy=CONTINUE} 才记录并推进。此缺省与本类 javadoc 承诺一致，
     * 与 NodeDelegateSupport（通知节点，I6 入队即推进语义）不同。
     */
    private boolean blockOnFailure(Map<String, Object> config) {
        Object strategy = config.get("failureStrategy");
        return strategy == null || "BLOCK".equalsIgnoreCase(String.valueOf(strategy));
    }

    /** 回查拒绝记录（recordRejected 独立事务提交）；无记录则为真实故障原样上抛。 */
    private FormTxnActionPort.TxnActionResult loadRejectedResult(
            String invocationKey, RuntimeException rollback) {
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
                applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        java.util.List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, error_code, error_msg FROM sw_form_txn_invocation"
                        + " WHERE invocation_key = ? AND status = 'REJECTED'"
                        + " ORDER BY create_time DESC LIMIT 1", invocationKey);
        if (rows.isEmpty()) {
            throw rollback;
        }
        Map<String, Object> row = rows.get(0);
        Integer errorCode = row.get("error_code") == null ? null
                : Integer.valueOf(String.valueOf(row.get("error_code")));
        String errorMsg = row.get("error_msg") == null ? null : String.valueOf(row.get("error_msg"));
        return new FormTxnActionPort.TxnActionResult(String.valueOf(row.get("id")), "REJECTED",
                null, null, null, null, null, errorCode, errorMsg, null, false);
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
