package com.sw.ck.bpm.engine.listener;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.common.exception.BaseException;
import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 动态并行分支任务监听器（I4 §3.1）：分支创建冻结快照、完成记票并回写分支动作。
 * 计数权威复用 I3 会签唯一键端口（ConsensusVotePort），保证并发/重复请求只结算一次。
 */
@Component("dynamicBranchTaskListener")
public class DynamicBranchTaskListener implements TaskListener {
    private static final Logger log = LoggerFactory.getLogger(DynamicBranchTaskListener.class);
    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private final RuntimeService runtimeService;
    private final ParticipantSnapshotRecorder snapshotRecorder;
    private final ObjectProvider<ConsensusVotePort> votePortProvider;
    private final ObjectProvider<DynamicBranchPort> branchPortProvider;

    @Autowired
    public DynamicBranchTaskListener(RuntimeService runtimeService,
                                     ObjectProvider<ParticipantSnapshotRecorder> recorder,
                                     ObjectProvider<ConsensusVotePort> votePortProvider,
                                     ObjectProvider<DynamicBranchPort> branchPortProvider) {
        this.runtimeService = runtimeService;
        this.snapshotRecorder = recorder.getIfAvailable();
        this.votePortProvider = votePortProvider;
        this.branchPortProvider = branchPortProvider;
    }

    @Override
    public void notify(DelegateTask task) {
        Object participant = task.getVariableLocal("participantId");
        if (participant == null) participant = task.getVariable("participantId");
        String leaderId = participant == null ? null : String.valueOf(participant);
        if ("create".equals(task.getEventName())) {
            if (snapshotRecorder != null && leaderId != null) {
                snapshotRecorder.record(task.getProcessInstanceId(), task.getTaskDefinitionKey(),
                        task.getId(), java.util.List.of(leaderId),
                        java.util.Collections.emptyMap(), tenantOf(task))
                .orElseThrow(() -> new IllegalStateException(
                        "ParticipantSnapshotRecorder#record 契约恒 present，empty 属契约违约"));
            }
            recordBranchAction(task, leaderId, "START", null);
            return;
        }
        if ("complete".equals(task.getEventName()) && runtimeService != null) {
            String rawOutcome = String.valueOf(task.getVariable("outcome"));
            String outcome = rawOutcome == null
                    || rawOutcome.isBlank()
                    || "DISAPPROVED".equalsIgnoreCase(rawOutcome)
                    || "REJECTED".equalsIgnoreCase(rawOutcome) ? "DISAPPROVE" : "APPROVE";
            String lockKey = task.getProcessInstanceId() + ":" + task.getTaskDefinitionKey();
            synchronized (LOCKS.computeIfAbsent(lockKey, key -> new Object())) {
                String actionKey = "consensusAction:" + task.getId();
                if (runtimeService.getVariable(task.getProcessInstanceId(), actionKey) != null) return;
                boolean counted = false;
                boolean votePortUnavailable = false;
                ConsensusVotePort votePort = votePortProvider == null ? null : votePortProvider.getIfAvailable();
                if (votePort != null) {
                    // empty = 投票服务不可用：不得据此认为重复，按旧变量计数兜底
                    java.util.Optional<Boolean> recorded = votePort.record(
                            String.valueOf(task.getVariable("tenantId")),
                            task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getId(),
                            task.getAssignee() == null ? leaderId : task.getAssignee(), outcome);
                    if (recorded.isPresent()) {
                        counted = recorded.orElseThrow();
                    } else {
                        votePortUnavailable = true;
                    }
                }
                runtimeService.setVariable(task.getProcessInstanceId(), actionKey, outcome);
                if (counted || votePortUnavailable || votePort == null) {
                    String counter = "APPROVE".equals(outcome)
                            ? "consensusApprovedCount" : "consensusRejectedCount";
                    Object current = runtimeService.getVariable(task.getProcessInstanceId(), counter);
                    int count = current == null ? 0 : Integer.parseInt(String.valueOf(current));
                    runtimeService.setVariable(task.getProcessInstanceId(), counter, count + 1);
                }
            }
            recordBranchAction(task, leaderId, outcome, null);
        }
    }

    private void recordBranchAction(DelegateTask task, String leaderId, String action, String reason) {
        if (leaderId == null) return;
        DynamicBranchPort port = branchPortProvider == null ? null : branchPortProvider.getIfAvailable();
        if (port == null) {
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_LEADER_MISSING.getCode(),
                    "动态分支记录端口不可用");
        }
        java.util.Optional<MutationOutcome> recorded = port.recordAction(
                String.valueOf(task.getVariable("tenantId")), task.getProcessInstanceId(),
                task.getTaskDefinitionKey(), leaderId, task.getId(), action, reason);
        if (recorded.isEmpty()) {
            // 契约 empty = 该分支快照尚未冻结（防御路径）：动作未落账，必须显式报告，不得静默当作成功
            log.warn("动态分支动作未落账：分支快照尚未冻结 processInstanceId={}, nodeKey={}, leaderId={}, action={}",
                    task.getProcessInstanceId(), task.getTaskDefinitionKey(), leaderId, action);
        } else {
            MutationOutcome outcome = recorded.orElseThrow();
            if (outcome == MutationOutcome.ALREADY_APPLIED) {
                log.debug("动态分支动作已存在，未产生第二次效果 processInstanceId={}, nodeKey={}, leaderId={}, action={}",
                        task.getProcessInstanceId(), task.getTaskDefinitionKey(), leaderId, action);
            }
        }
    }

    private Long tenantOf(DelegateTask task) {
        try {
            return Long.valueOf(String.valueOf(task.getVariable("tenantId")));
        } catch (Exception e) {
            return null;
        }
    }
}
