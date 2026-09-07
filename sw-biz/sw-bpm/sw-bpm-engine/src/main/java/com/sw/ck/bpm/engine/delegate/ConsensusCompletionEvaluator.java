package com.sw.ck.bpm.engine.delegate;

import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** 会签提前结算判定；动作计数由会签任务监听器在同一流程实例变量中维护。 */
@Component("consensusCompletionEvaluator")
public class ConsensusCompletionEvaluator {
    public boolean shouldComplete(DelegateExecution execution, String mode, int ratio) {
        int total = number(execution.getVariable("consensusTotal"), number(execution.getVariable("nrOfInstances"), 0));
        int approved = number(execution.getVariable("consensusApprovedCount"), 0);
        int rejected = number(execution.getVariable("consensusRejectedCount"), 0);
        int completed = number(execution.getVariable("nrOfCompletedInstances"), 0);
        // ANY 的 REJECT 是终止性负向结果，不能仅按 Flowable 已完成实例数判为通过。
        if ("ANY".equals(mode)) return rejected > 0 || approved >= 1;
        if ("ALL".equals(mode)) return rejected > 0 || (total > 0 && approved >= total);
        if (total <= 0) return false;
        // 产品比例按向上取整结算：ceil(total * threshold / 100)。
        // 因此 3 人、67% 必须 3 票，禁止把 2/3 的 66.67% 四舍五入为 67%。
        int required = Math.max(1, (int) Math.ceil(total * ratio / 100.0d));
        return approved >= required || (total - completed + approved) < required;
    }

    private int number(Object value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
