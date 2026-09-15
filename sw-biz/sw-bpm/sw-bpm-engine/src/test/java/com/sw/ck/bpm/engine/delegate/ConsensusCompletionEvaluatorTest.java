package com.sw.ck.bpm.engine.delegate;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsensusCompletionEvaluatorTest {

    private final ConsensusCompletionEvaluator evaluator = new ConsensusCompletionEvaluator();

    @Test
    void anyRejectMustNotSettleBeforeAllVoted() {
        // I3 审查 02 裁决：ANY 负向不得在第一张否决票提前终结，须等全部参与人表决完成。
        DelegateExecution execution = execution(2, 0, 1, 1);

        assertThat(evaluator.shouldComplete(execution, "ANY", 100)).isFalse();
    }

    @Test
    void anyRejectSettlesNegativeAfterAllVotedWithoutApproval() {
        // 全员否决（0 通过 / 2 完成）→ ANY 负向结算。
        DelegateExecution execution = execution(2, 0, 2, 2);

        assertThat(evaluator.shouldComplete(execution, "ANY", 100)).isTrue();
    }

    @Test
    void anyApproveMustSettleAsPositive() {
        DelegateExecution execution = execution(2, 1, 0, 1);

        assertThat(evaluator.shouldComplete(execution, "ANY", 100)).isTrue();
    }

    @Test
    void ratioMustNotSettleBeforeThreshold() {
        DelegateExecution execution = execution(3, 1, 0, 1);

        assertThat(evaluator.shouldComplete(execution, "RATIO", 67)).isFalse();
    }

    @Test
    void ratio66OfThreeRequiresTwoApprovals() {
        DelegateExecution execution = execution(3, 2, 0, 2);

        assertThat(evaluator.shouldComplete(execution, "RATIO", 66)).isTrue();
    }

    @Test
    void ratio67OfThreeRequiresThreeApprovals() {
        DelegateExecution execution = execution(3, 2, 0, 2);

        assertThat(evaluator.shouldComplete(execution, "RATIO", 67)).isFalse();
        when(execution.getVariable("consensusApprovedCount")).thenReturn(3);
        when(execution.getVariable("nrOfCompletedInstances")).thenReturn(3);
        assertThat(evaluator.shouldComplete(execution, "RATIO", 67)).isTrue();
    }

    private DelegateExecution execution(int total, int approved, int rejected, int completed) {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("consensusTotal")).thenReturn(total);
        when(execution.getVariable("consensusApprovedCount")).thenReturn(approved);
        when(execution.getVariable("consensusRejectedCount")).thenReturn(rejected);
        when(execution.getVariable("nrOfCompletedInstances")).thenReturn(completed);
        when(execution.getVariable("nrOfInstances")).thenReturn(total);
        return execution;
    }
}
