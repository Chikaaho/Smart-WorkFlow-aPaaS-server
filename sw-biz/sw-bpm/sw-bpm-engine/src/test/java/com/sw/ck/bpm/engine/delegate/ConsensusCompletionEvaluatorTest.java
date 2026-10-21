package com.sw.ck.bpm.engine.delegate;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsensusCompletionEvaluatorTest {

    private final ConsensusCompletionEvaluator evaluator = new ConsensusCompletionEvaluator();

    private void printCase(String mode, int threshold, int total, int approved, int rejected,
                           int completed, boolean result, String scenario) {
        System.out.println("[P63-EV] g04b.evaluator scenario=" + scenario + " mode=" + mode
                + " threshold=" + threshold + " total=" + total + " approved=" + approved
                + " rejected=" + rejected + " completed=" + completed
                + " shouldComplete=" + result);
    }

    @Test
    void anyRejectMustNotSettleBeforeAllVoted() {
        // I3 审查 02 裁决：ANY 负向不得在第一张否决票提前终结，须等全部参与人表决完成。
        DelegateExecution execution = execution(2, 0, 1, 1);

        boolean result = evaluator.shouldComplete(execution, "ANY", 100);
        printCase("ANY", 100, 2, 0, 1, 1, result, "any-reject-before-all-voted");
        assertThat(result).isFalse();
    }

    @Test
    void anyRejectSettlesNegativeAfterAllVotedWithoutApproval() {
        // 全员否决（0 通过 / 2 完成）→ ANY 负向结算。
        DelegateExecution execution = execution(2, 0, 2, 2);

        boolean result = evaluator.shouldComplete(execution, "ANY", 100);
        printCase("ANY", 100, 2, 0, 2, 2, result, "any-reject-after-all-voted");
        assertThat(result).isTrue();
    }

    @Test
    void anyApproveMustSettleAsPositive() {
        DelegateExecution execution = execution(2, 1, 0, 1);

        boolean result = evaluator.shouldComplete(execution, "ANY", 100);
        printCase("ANY", 100, 2, 1, 0, 1, result, "any-approve-positive");
        assertThat(result).isTrue();
    }

    @Test
    void ratioMustNotSettleBeforeThreshold() {
        DelegateExecution execution = execution(3, 1, 0, 1);

        boolean result = evaluator.shouldComplete(execution, "RATIO", 67);
        printCase("RATIO", 67, 3, 1, 0, 1, result, "ratio-below-threshold");
        assertThat(result).isFalse();
    }

    @Test
    void ratio66OfThreeRequiresTwoApprovals() {
        DelegateExecution execution = execution(3, 2, 0, 2);

        boolean result = evaluator.shouldComplete(execution, "RATIO", 66);
        printCase("RATIO", 66, 3, 2, 0, 2, result, "ratio-66-of-3-two-approvals");
        assertThat(result).isTrue();
    }

    @Test
    void ratio67OfThreeRequiresThreeApprovals() {
        DelegateExecution execution = execution(3, 2, 0, 2);

        boolean below = evaluator.shouldComplete(execution, "RATIO", 67);
        printCase("RATIO", 67, 3, 2, 0, 2, below, "ratio-67-of-3-two-approvals");
        assertThat(below).isFalse();
        when(execution.getVariable("consensusApprovedCount")).thenReturn(3);
        when(execution.getVariable("nrOfCompletedInstances")).thenReturn(3);
        boolean reached = evaluator.shouldComplete(execution, "RATIO", 67);
        printCase("RATIO", 67, 3, 3, 0, 3, reached, "ratio-67-of-3-three-approvals");
        assertThat(reached).isTrue();
    }

    @Test
    void countModeRequiresConfiguredApprovals() {
        DelegateExecution execution = execution(3, 1, 0, 1);

        boolean below = evaluator.shouldComplete(execution, "COUNT", 2);
        printCase("COUNT", 2, 3, 1, 0, 1, below, "count-2-of-3-one-approval");
        assertThat(below).isFalse();

        when(execution.getVariable("consensusApprovedCount")).thenReturn(2);
        when(execution.getVariable("nrOfCompletedInstances")).thenReturn(2);
        boolean reached = evaluator.shouldComplete(execution, "COUNT", 2);
        printCase("COUNT", 2, 3, 2, 0, 2, reached, "count-2-of-3-two-approvals");
        assertThat(reached).isTrue();
    }

    @Test
    void countAtOrAboveTotalRequiresAllApprovals() {
        DelegateExecution execution = execution(3, 2, 0, 2);

        boolean below = evaluator.shouldComplete(execution, "COUNT", 5);
        printCase("COUNT", 5, 3, 2, 0, 2, below, "count-above-total-two-approvals");
        assertThat(below).isFalse();

        when(execution.getVariable("consensusApprovedCount")).thenReturn(3);
        when(execution.getVariable("nrOfCompletedInstances")).thenReturn(3);
        boolean reached = evaluator.shouldComplete(execution, "COUNT", 5);
        printCase("COUNT", 5, 3, 3, 0, 3, reached, "count-above-total-all-approved");
        assertThat(reached).isTrue();
    }

    @Test
    void countModeSettlesNegativeWhenAllVotedWithoutEnoughApprovals() {
        DelegateExecution execution = execution(3, 1, 2, 3);

        boolean result = evaluator.shouldComplete(execution, "COUNT", 2);
        printCase("COUNT", 2, 3, 1, 2, 3, result, "count-all-voted-not-enough");
        assertThat(result).isTrue();
    }

    @Test
    void asyncJobRestoresTenantContextAndClearsIt() {
        // 异步作业线程无登录态：按执行租户还原身份完成计数查询，结束后清理，不泄漏到线程池。
        DelegateExecution execution = execution(2, 2, 0, 2);
        when(execution.getTenantId()).thenReturn("9001");
        when(execution.getVariable("tenantId")).thenReturn(9001L);
        when(execution.getVariable("submitter")).thenReturn(91103L);
        com.sw.ck.security.holder.LoginUserHolder.clear();

        boolean result = evaluator.shouldComplete(execution, "ALL", 100);

        assertThat(result).isTrue();
        assertThat(com.sw.ck.security.holder.LoginUserHolder.get()).isNull();

        // 已有登录态时不覆盖调用方身份
        com.sw.ck.security.holder.LoginUser caller = new com.sw.ck.security.holder.LoginUser();
        caller.setTenantId(9002L);
        com.sw.ck.security.holder.LoginUserHolder.set(caller);
        try {
            assertThat(evaluator.shouldComplete(execution, "ALL", 100)).isTrue();
            assertThat(com.sw.ck.security.holder.LoginUserHolder.get().getTenantId()).isEqualTo(9002L);
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    private DelegateExecution execution(int total, int approved, int rejected, int completed) {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("consensusTotal")).thenReturn(total);
        when(execution.getVariable("consensusApprovedCount")).thenReturn(approved);
        when(execution.getVariable("consensusRejectedCount")).thenReturn(rejected);
        when(execution.getVariable("nrOfCompletedInstances")).thenReturn(completed);
        when(execution.getVariable("nrOfInstances")).thenReturn(total);
        when(execution.getTenantId()).thenReturn("9001");
        when(execution.getVariable("tenantId")).thenReturn(9001L);
        return execution;
    }
}
