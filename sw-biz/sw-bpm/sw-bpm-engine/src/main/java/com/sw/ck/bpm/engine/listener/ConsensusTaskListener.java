package com.sw.ck.bpm.engine.listener;

import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.system.api.user.UserQueryFacade;
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
    private final UserQueryFacade userQueryFacade;
    /** 会签 DB 计数端口（I3）：多实例唯一键替代单 JVM synchronized。 */
    @Autowired(required = false)
    private transient org.springframework.beans.factory.ObjectProvider<com.sw.ck.bpm.api.participant.ConsensusVotePort> votePortProvider;

    public ConsensusTaskListener(org.springframework.beans.factory.ObjectProvider<ParticipantSnapshotRecorder> recorder) {
        this(null, recorder, null);
    }

    @Autowired
    public ConsensusTaskListener(RuntimeService runtimeService,
                                 org.springframework.beans.factory.ObjectProvider<ParticipantSnapshotRecorder> recorder,
                                 org.springframework.beans.factory.ObjectProvider<UserQueryFacade> userQueryFacade) {
        this.runtimeService = runtimeService;
        this.snapshotRecorder = recorder.getIfAvailable();
        this.userQueryFacade = userQueryFacade == null ? null : userQueryFacade.getIfAvailable();
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
            // 冻结参与人展示名（I1）：会签子任务同样不随后续改名被重写
            java.util.Map<String, String> displayNames = java.util.Collections.emptyMap();
            if (userQueryFacade != null) {
                try {
                    Long pid = null;
                    try { pid = Long.valueOf(String.valueOf(participant)); } catch (Exception ignored) { }
                    if (pid != null) {
                        // empty = 查询对象缺失：无展示名可冻结，快照仅记录 ID
                        java.util.Optional<java.util.Map<Long, String>> names =
                                userQueryFacade.getUserDisplayNames(java.util.List.of(pid));
                        if (names.isPresent()) {
                            String name = names.orElseThrow().get(pid);
                            if (name != null) {
                                displayNames = java.util.Map.of(String.valueOf(participant), name);
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }
            snapshotRecorder.record(task.getProcessInstanceId(), task.getTaskDefinitionKey(), task.getId(),
                    java.util.List.of(String.valueOf(participant)), displayNames, tenant)
            .orElseThrow(() -> new IllegalStateException(
                    "ParticipantSnapshotRecorder#record 契约恒 present，empty 属契约违约"));
        }
        // 分母权威已迁移：ConsensusVotePort.total（进入节点冻结的快照人数）。
        // 首个子任务创建时 nrOfInstances 可能尚未完整（并行多实例逐个建执行），
        // 在此缓存 consensusTotal 会把分母钉死为 1，导致 ALL 提前结算——禁止回退该做法。
        if ("complete".equals(task.getEventName())) {
            // I3：会签动作计数权威走 DB 阻断唯一键（ConsensusVotePort，多实例安全），
            // 不再仅依赖单 JVM synchronized。DISAPPROVED/REJECTED 均计负向。
            String rawOutcome = String.valueOf(task.getVariable("outcome"));
            String outcome = rawOutcome == null
                    || rawOutcome.isBlank()
                    || "DISAPPROVED".equalsIgnoreCase(rawOutcome)
                    || "REJECTED".equalsIgnoreCase(rawOutcome) ? "DISAPPROVE" : "APPROVE";
            if (runtimeService == null) {
                String counter = "APPROVE".equals(outcome)
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
                boolean counted = false;
                boolean votePortUnavailable = false;
                if (votePortProvider != null) {
                    com.sw.ck.bpm.api.participant.ConsensusVotePort port = votePortProvider.getIfAvailable();
                    if (port != null) {
                        // empty = 投票服务不可用：不得据此认为重复，按旧变量计数兜底
                        java.util.Optional<Boolean> recorded = port.record(
                                String.valueOf(task.getVariable("tenantId")),
                                task.getProcessInstanceId(), task.getTaskDefinitionKey(),
                                task.getId(),
                                task.getAssignee() == null
                                        ? String.valueOf(task.getVariable("participantId"))
                                        : task.getAssignee(),
                                outcome);
                        if (recorded.isPresent()) {
                            counted = recorded.orElseThrow();
                        } else {
                            votePortUnavailable = true;
                        }
                    }
                }
                runtimeService.setVariable(task.getProcessInstanceId(), actionKey, outcome);
                // 只在形成新的合法计数时更新变量缓存；端口不可用时按旧变量计数兜底
                if (counted || votePortUnavailable || votePortProvider == null
                        || votePortProvider.getIfAvailable() == null) {
                    String counter = "APPROVE".equals(outcome)
                            ? "consensusApprovedCount" : "consensusRejectedCount";
                    Object current = runtimeService.getVariable(task.getProcessInstanceId(), counter);
                    int count = current == null ? 0 : Integer.parseInt(String.valueOf(current));
                    runtimeService.setVariable(task.getProcessInstanceId(), counter, count + 1);
                }
            }
        }
    }
}
