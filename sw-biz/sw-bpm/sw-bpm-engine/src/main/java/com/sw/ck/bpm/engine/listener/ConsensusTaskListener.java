package com.sw.ck.bpm.engine.listener;

import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 会签子任务监听器：将多实例元素变量绑定为 assignee，并维护动作计数。 */
@Component("consensusTaskListener")
public class ConsensusTaskListener implements TaskListener {
    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private final RuntimeService runtimeService;
    private final ParticipantSnapshotRecorder snapshotRecorder;

    public ConsensusTaskListener(org.springframework.beans.factory.ObjectProvider<ParticipantSnapshotRecorder> recorder) {
        this(null, recorder);
    }

    @Autowired
    public ConsensusTaskListener(RuntimeService runtimeService,
                                 org.springframework.beans.factory.ObjectProvider<ParticipantSnapshotRecorder> recorder) {
        this.runtimeService = runtimeService;
        this.snapshotRecorder = recorder.getIfAvailable();
    }

    @Override
    public void notify(DelegateTask task) {
        // Flowable 多实例元素变量绑定在子执行上下文；优先读取 task local，
        // 避免在批量创建/完成期间沿父执行上下文读到上一条参与人值。
        Object participant = task.getVariableLocal("participantId");
        if (participant == null) participant = task.getVariable("participantId");
        if ("create".equals(task.getEventName()) && snapshotRecorder != null && participant != null) {
            Long tenant = null;
            try { tenant = Long.valueOf(String.valueOf(task.getVariable("tenantId"))); }
            catch (Exception ignored) { }
            snapshotRecorder.record(task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getId(),
                    java.util.List.of(String.valueOf(participant)), tenant);
        }
        if ("create".equals(task.getEventName()) && runtimeService != null) {
            Object total = runtimeService.getVariable(task.getProcessInstanceId(), "consensusTotal");
            Object instances = task.getVariable("nrOfInstances");
            if (total == null && instances != null) {
                runtimeService.setVariable(task.getProcessInstanceId(), "consensusTotal", instances);
            }
        }
        if ("complete".equals(task.getEventName())) {
            String outcome = "REJECTED".equalsIgnoreCase(String.valueOf(task.getVariable("outcome")))
                    ? "REJECTED" : "APPROVED";
            if (runtimeService == null) {
                String counter = "APPROVED".equals(outcome)
                        ? "consensusApprovedCount" : "consensusRejectedCount";
                Object current = task.getVariable(counter);
                int count = current == null ? 0 : Integer.parseInt(String.valueOf(current));
                task.setVariable(counter, count + 1);
                return;
            }
            String lockKey = task.getProcessInstanceId() + ":" + task.getTaskDefinitionKey();
            synchronized (LOCKS.computeIfAbsent(lockKey, key -> new Object())) {
                String actionKey = "consensusAction:" + task.getId();
                if (runtimeService.getVariable(task.getProcessInstanceId(), actionKey) != null) return;
                runtimeService.setVariable(task.getProcessInstanceId(), actionKey, outcome);
                String counter = "APPROVED".equals(outcome)
                        ? "consensusApprovedCount" : "consensusRejectedCount";
                Object current = runtimeService.getVariable(task.getProcessInstanceId(), counter);
                int count = current == null ? 0 : Integer.parseInt(String.valueOf(current));
                runtimeService.setVariable(task.getProcessInstanceId(), counter, count + 1);
            }
        }
    }
}
