package com.sw.ck.notify.impl;

import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.service.NotifyMessageService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotifyFacadeAdapterIdempotencyTest {

    @Test
    void replaySameIdempotencyKeyCallsAdapterOnceAndPersistsOnce() {
        NotifyMessageService messageService = mock(NotifyMessageService.class);
        NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
        when(adapter.channel()).thenReturn(NotifyChannel.SMS);
        when(adapter.send(any())).thenReturn(NotifySendResult.builder()
                .channel(NotifyChannel.SMS)
                .status("SUCCESS")
                .externalMessageId("p58_ext_z7")
                .build());

        NotifyMessage existing = new NotifyMessage();
        existing.setDeliveryStatus("SUCCESS");
        existing.setExternalMessageId("p58_ext_z7");
        when(messageService.findByIdempotencyKey("P58_Z7_REPLAY_20260904"))
                .thenReturn(null)
                .thenReturn(existing);

        NotifyFacadeImpl facade = new NotifyFacadeImpl(messageService, java.util.List.of(adapter));
        NotifySendRequest request = NotifySendRequest.builder()
                .recipientId(2095490569284018177L)
                .title("P58 Z7 replay")
                .content("P58 Z7 replay")
                .bizType(NotifyBizType.WF_APPROVED)
                .bizId("p58-z7-replay")
                .tenantId(0L)
                .channel(NotifyChannel.SMS)
                .idempotencyKey("P58_Z7_REPLAY_20260904")
                .build();

        NotifySendResult first = facade.send(request);
        NotifySendResult replay = facade.send(request);

        assertThat(first.getStatus()).isEqualTo("SUCCESS");
        assertThat(replay.getStatus()).isEqualTo("SUCCESS");
        assertThat(replay.getExternalMessageId()).isEqualTo("p58_ext_z7");
        verify(adapter, times(1)).send(request);
        verify(messageService, times(1)).save(any(NotifyMessage.class));
        System.out.println("[Z7-idempotency] key=P58_Z7_REPLAY_20260904 first=SUCCESS replay=SUCCESS adapter_calls=1 persisted_messages=1");
    }
}
