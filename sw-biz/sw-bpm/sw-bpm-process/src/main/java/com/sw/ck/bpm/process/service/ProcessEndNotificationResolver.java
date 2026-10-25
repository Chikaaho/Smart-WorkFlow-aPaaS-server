package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessNotificationConfig;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 结束通知接收人/渠道解析（50:88 / W020）。
 *
 * <p>配置来自实例启动时冻结的流程版本 graph_json（旧实例使用旧配置）；无配置的旧定义
 * 保持原「通知发起人 + 路由渠道」行为。其他接收人按当前租户启用用户复核；参与审批人
 * 取本实例参与人快照去重。</p>
 */
@Component
public class ProcessEndNotificationResolver {

    private static final Logger log = LoggerFactory.getLogger(ProcessEndNotificationResolver.class);

    private final NodeFormDataService nodeFormDataService;
    private final ParticipantSnapshotMapper participantSnapshotMapper;
    private final UserQueryFacade userQueryFacade;

    public ProcessEndNotificationResolver(NodeFormDataService nodeFormDataService,
                                          ParticipantSnapshotMapper participantSnapshotMapper,
                                          UserQueryFacade userQueryFacade) {
        this.nodeFormDataService = nodeFormDataService;
        this.participantSnapshotMapper = participantSnapshotMapper;
        this.userQueryFacade = userQueryFacade;
    }

    public List<BpmNotifyEvent> resolveApproved(BpmInstance instance, Long actorUserId) {
        if (instance == null || instance.getProcessInstanceId() == null) {
            return List.of();
        }
        ProcessGraph graph = null;
        try {
            graph = nodeFormDataService.loadGraph(instance.getProcessDefKey(), instance.getDefVersion());
        } catch (Exception e) {
            log.warn("结束通知读取流程版本图失败，按旧定义原行为处理: pi={}, defKey={}, defVersion={}, exceptionClass={}",
                    instance.getProcessInstanceId(), instance.getProcessDefKey(), instance.getDefVersion(),
                    e.getClass().getSimpleName());
        }
        ProcessNotificationConfig config = graph == null ? null : graph.getNotification();
        if (config == null) {
            return instance.getInitiatorId() == null
                    ? List.of()
                    : List.of(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED,
                            instance.getInitiatorId(), instance.getTenantId(), actorUserId,
                            instance.getProcessInstanceId()));
        }

        Set<Long> recipients = new LinkedHashSet<>();
        if (Boolean.TRUE.equals(config.getInitiator()) && instance.getInitiatorId() != null) {
            recipients.add(instance.getInitiatorId());
        }
        if (Boolean.TRUE.equals(config.getApprovers())) {
            recipients.addAll(approverIds(instance.getProcessInstanceId()));
        }
        if (config.getOtherRecipientIds() != null && !config.getOtherRecipientIds().isEmpty()) {
            List<Long> configured = config.getOtherRecipientIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            List<Long> active = userQueryFacade.findActiveUserIds(configured, instance.getTenantId())
                    .orElseGet(() -> {
                        log.warn("结束通知其他接收人租户上下文不可用，跳过其他接收人: pi={}, tenantId={}",
                                instance.getProcessInstanceId(), instance.getTenantId());
                        return List.of();
                    });
            recipients.addAll(active);
        }
        if (recipients.isEmpty()) {
            log.info("结束通知配置无可解析接收人，跳过: pi={}, defKey={}",
                    instance.getProcessInstanceId(), instance.getProcessDefKey());
            return List.of();
        }
        List<String> channels = config.getChannels();
        List<BpmNotifyEvent> events = new ArrayList<>(recipients.size());
        for (Long recipientId : recipients) {
            boolean initiatorRecipient = Objects.equals(recipientId, instance.getInitiatorId());
            events.add(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, recipientId,
                    instance.getTenantId(), actorUserId, instance.getProcessInstanceId(), 1L,
                    channels, initiatorRecipient));
        }
        log.debug("结束通知已解析: pi={}, recipients={}, channels={}",
                instance.getProcessInstanceId(), recipients, channels);
        return events;
    }

    private Set<Long> approverIds(String processInstanceId) {
        List<ParticipantSnapshot> snapshots = participantSnapshotMapper.selectList(
                Wrappers.<ParticipantSnapshot>lambdaQuery()
                        .eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId));
        Set<Long> ids = new LinkedHashSet<>();
        for (ParticipantSnapshot snapshot : snapshots) {
            String participantId = snapshot.getParticipantId();
            if (participantId == null || participantId.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(participantId));
            } catch (NumberFormatException e) {
                log.warn("参与人快照 ID 非数字，跳过: pi={}, participantId={}",
                        processInstanceId, participantId);
            }
        }
        return ids;
    }
}
