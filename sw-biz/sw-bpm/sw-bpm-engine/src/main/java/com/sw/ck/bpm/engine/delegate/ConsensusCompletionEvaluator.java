package com.sw.ck.bpm.engine.delegate;

import com.sw.ck.bpm.api.participant.ConsensusSettlementPort;
import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 会签提前结算判定（I3 §4.5）。
 * <p>
 * 计数权威 = {@link ConsensusVotePort}（数据库唯一键，多实例安全）；
 * 端口不可用时回退流程变量。VETO（一票否决）：任何否决即节点负向。
 * 负向结果经 {@link ConsensusSettlementPort} 触发终局结算，幂等。
 * </p>
 */
@Component("consensusCompletionEvaluator")
public class ConsensusCompletionEvaluator {

    private ObjectProvider<ConsensusVotePort> votePort;
    private ObjectProvider<ConsensusSettlementPort> settlementPort;

    @Autowired
    public ConsensusCompletionEvaluator(ObjectProvider<ConsensusVotePort> votePort,
                                        ObjectProvider<ConsensusSettlementPort> settlementPort) {
        this.votePort = votePort;
        this.settlementPort = settlementPort;
    }

    /** 兼容旧行为的单测/静态装配：无端口时回退流程变量计数。 */
    public ConsensusCompletionEvaluator() {
        this.pay(null, null);
    }

    private void pay(ObjectProvider<ConsensusVotePort> votePort,
                     ObjectProvider<ConsensusSettlementPort> settlementPort) {
        this.votePort = votePort;
        this.settlementPort = settlementPort;
    }

    public boolean shouldComplete(DelegateExecution execution, String mode, int ratio) {
        int total = resolveTotal(execution);
        int approved = count(execution, "APPROVE", "consensusApprovedCount");
        int rejected = count(execution, "DISAPPROVE", "consensusRejectedCount");
        int completed = number(execution.getVariable("nrOfCompletedInstances"), 0);

        boolean positive;
        boolean negative;
        if ("ANY".equals(mode)) {
            positive = approved >= 1;
            // ANY 负向阈值与 RATIO 同口径：任一否决不立即终结，
            // 须等参与人全部表决完成（completed >= total）且无足够通过票；
            // 一票否决语义由 VETO 承担，不得混入 ANY。
            negative = !positive && completed >= total;
        } else if ("VETO".equals(mode)) {
            // 一票否决：任何否决即节点负向
            positive = approved >= total;
            negative = rejected > 0;
        } else if ("ALL".equals(mode)) {
            positive = total > 0 && approved >= total;
            negative = !positive && rejected > 0;
        } else {
            if (total <= 0) {
                return false;
            }
            // 产品比例按向上取整结算：ceil(total * threshold / 100)。
            // 因此 3 人、67% 必须 3 票，禁止把 2/3 的 66.67% 四舍五入为 67%。
            int required = Math.max(1, (int) Math.ceil(total * ratio / 100.0d));
            positive = approved >= required;
            negative = !positive && completed >= total && approved < required;
        }
        if (negative) {
            // 负向终局：经端口结算（幂等），元素不再继续向后推进
            if (settlementPort != null) {
                settlementPort.ifAvailable(port -> port.onNegativeSettlement(
                        String.valueOf(execution.getVariable("tenantId")),
                        execution.getProcessInstanceId(),
                        currentActivityId(execution),
                        mode + ":rejected=" + rejected));
            }
            return true;
        }
        return positive;
    }

    /** 分母权威 = 进入节点时冻结的快照人数（端口）；变量口径仅作端口/快照不可用回退。 */
    private int resolveTotal(DelegateExecution execution) {
        ConsensusVotePort port = votePort == null ? null : votePort.getIfAvailable();
        if (port != null) {
            // empty = 无冻结快照（端口不可用或快照缺失）：回退变量口径（原 -1 哨兵路径）
            java.util.Optional<Long> frozen = port.total(
                    String.valueOf(execution.getVariable("tenantId")),
                    execution.getProcessInstanceId(), currentActivityId(execution));
            if (frozen.isPresent()) {
                return frozen.orElseThrow().intValue();
            }
        }
        return number(execution.getVariable("consensusTotal"),
                number(execution.getVariable("nrOfInstances"), 0));
    }

    private String currentActivityId(DelegateExecution execution) {
        return execution.getCurrentActivityId() == null ? "UNKNOWN"
                : execution.getCurrentActivityId();
    }

    private int count(DelegateExecution execution, String outcome, String variableFallback) {
        ConsensusVotePort port = votePort == null ? null : votePort.getIfAvailable();
        if (port != null) {
            // empty = 端口不可用：按既有回退口径读取变量计数（原 -1 哨兵路径）
            java.util.Optional<Long> counted = port.count(
                    String.valueOf(execution.getVariable("tenantId")),
                    execution.getProcessInstanceId(), currentActivityId(execution), outcome);
            if (counted.isPresent()) {
                return counted.orElseThrow().intValue();
            }
        }
        return number(execution.getVariable(variableFallback), 0);
    }

    private int number(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
