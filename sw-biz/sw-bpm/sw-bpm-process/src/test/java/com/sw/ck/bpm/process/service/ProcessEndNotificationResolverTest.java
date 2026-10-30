package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessNotificationConfig;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessEndNotificationResolverTest {

    private final NodeFormDataService nodeFormDataService = mock(NodeFormDataService.class);
    private final ParticipantSnapshotMapper participantSnapshotMapper = mock(ParticipantSnapshotMapper.class);
    private final UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
    private final ProcessEndNotificationResolver resolver = new ProcessEndNotificationResolver(
            nodeFormDataService, participantSnapshotMapper, userQueryFacade);

    private static BpmInstance instance() {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId("pi-1");
        instance.setProcessDefKey("def-1");
        instance.setDefVersion(2);
        instance.setTenantId(100L);
        instance.setInitiatorId(9001L);
        return instance;
    }

    private static ParticipantSnapshot participant(String id) {
        ParticipantSnapshot snapshot = new ParticipantSnapshot();
        snapshot.setParticipantId(id);
        return snapshot;
    }

    @Test
    void legacyDefinitionKeepsInitiatorOnlyRouting() {
        when(nodeFormDataService.loadGraph("def-1", 2)).thenReturn(ProcessGraph.builder().build());
        List<BpmNotifyEvent> events = resolver.resolveApproved(instance(), 9001L);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getTrigger()).isEqualTo(BpmNotifyTrigger.PROCESS_APPROVED);
        assertThat(events.get(0).getRecipientId()).isEqualTo(9001L);
        assertThat(events.get(0).getChannels()).isNull();
    }

    @Test
    void configuredTargetsResolveDedupedRecipientsAndChannels() {
        ProcessNotificationConfig config = ProcessNotificationConfig.builder()
                .initiator(true)
                .approvers(true)
                .otherRecipientIds(List.of(9003L))
                .channels(List.of("IN_APP", "EMAIL"))
                .build();
        when(nodeFormDataService.loadGraph("def-1", 2))
                .thenReturn(ProcessGraph.builder().notification(config).build());
        when(participantSnapshotMapper.selectList(any()))
                .thenReturn(List.of(participant("9001"), participant("9002"), participant("9001")));
        when(userQueryFacade.findActiveUserIds(List.of(9003L), 100L))
                .thenReturn(Optional.of(List.of(9003L)));

        List<BpmNotifyEvent> events = resolver.resolveApproved(instance(), 9002L);
        assertThat(events).extracting(BpmNotifyEvent::getRecipientId)
                .containsExactly(9001L, 9002L, 9003L);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getChannels()).containsExactly("IN_APP", "EMAIL");
            assertThat(event.getBizId()).isEqualTo("pi-1");
            assertThat(event.getTenantId()).isEqualTo(100L);
            assertThat(event.isInitiatorRecipient()).isEqualTo(event.getRecipientId() == 9001L);
        });
    }

    @Test
    void inactiveOtherRecipientIsNotDelivered() {
        ProcessNotificationConfig config = ProcessNotificationConfig.builder()
                .otherRecipientIds(List.of(9003L))
                .channels(List.of("IN_APP"))
                .build();
        when(nodeFormDataService.loadGraph("def-1", 2))
                .thenReturn(ProcessGraph.builder().notification(config).build());
        when(userQueryFacade.findActiveUserIds(List.of(9003L), 100L))
                .thenReturn(Optional.of(List.of()));

        assertThat(resolver.resolveApproved(instance(), 9002L)).isEmpty();
    }

    @Test
    void emptyConfigTargetsProduceNoEvent() {
        ProcessNotificationConfig config = ProcessNotificationConfig.builder()
                .initiator(false)
                .approvers(false)
                .channels(List.of("IN_APP"))
                .build();
        when(nodeFormDataService.loadGraph("def-1", 2))
                .thenReturn(ProcessGraph.builder().notification(config).build());

        assertThat(resolver.resolveApproved(instance(), 9002L)).isEmpty();
    }
}
