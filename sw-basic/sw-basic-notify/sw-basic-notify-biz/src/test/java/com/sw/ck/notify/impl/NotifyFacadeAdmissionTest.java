package com.sw.ck.notify.impl;

import com.sw.ck.notify.api.*;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.service.NotifyMessageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotifyFacadeAdmissionTest {
    @BeforeAll
    static void tableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), NotifyMessage.class);
    }

    @Test
    void stoppedTenantKeepsPendingWithoutCallingAdapterOrChangingRow() {
        var messages = mock(NotifyMessageService.class);
        var adapter = mock(NotifyChannelAdapter.class);
        when(adapter.channel()).thenReturn(Optional.of(NotifyChannel.SMS));
        when(messages.findByIdempotencyKey("owned-key")).thenReturn(pending());
        var facade = new NotifyFacadeImpl(messages, List.of(adapter), null, (tenantId, claim) -> 0);
        assertThat(facade.send(request()).orElseThrow().getStatus()).isEqualTo("PENDING");
        verify(adapter, never()).send(any());
        verify(messages, never()).getBaseMapper();
        verify(messages, never()).save(any(NotifyMessage.class));
    }

    @Test
    void losingPersistentClaimNeverCallsAdapter() {
        var messages = mock(NotifyMessageService.class);
        var mapper = mock(NotifyMessageMapper.class);
        var adapter = mock(NotifyChannelAdapter.class);
        when(adapter.channel()).thenReturn(Optional.of(NotifyChannel.SMS));
        when(messages.findByIdempotencyKey("owned-key")).thenReturn(pending());
        when(messages.getBaseMapper()).thenReturn(mapper);
        when(mapper.update(isNull(), any())).thenReturn(0);
        var facade = new NotifyFacadeImpl(messages, List.of(adapter), null, (tenantId, claim) -> claim.getAsInt());
        assertThat(facade.send(request()).orElseThrow().getStatus()).isEqualTo("PENDING");
        verify(adapter, never()).send(any());
    }

    @Test
    void persistedClaimPrecedesExternalCallAndResultWrite() {
        var messages = mock(NotifyMessageService.class);
        var mapper = mock(NotifyMessageMapper.class);
        var adapter = mock(NotifyChannelAdapter.class);
        var writes = new AtomicInteger();
        when(adapter.channel()).thenReturn(Optional.of(NotifyChannel.SMS));
        when(messages.findByIdempotencyKey("owned-key")).thenReturn(pending());
        when(messages.getBaseMapper()).thenReturn(mapper);
        when(mapper.update(isNull(), any())).thenAnswer(call -> { writes.incrementAndGet(); return 1; });
        when(adapter.send(any())).thenAnswer(call -> {
            assertThat(writes).hasValue(1);
            return Optional.of(NotifySendResult.builder().channel(NotifyChannel.SMS).status("SUCCESS").build());
        });
        var facade = new NotifyFacadeImpl(messages, List.of(adapter), null, (tenantId, claim) -> claim.getAsInt());
        assertThat(facade.send(request()).orElseThrow().getStatus()).isEqualTo("SUCCESS");
        assertThat(writes).hasValue(2);
        verify(adapter).send(any());
    }

    private static NotifyMessage pending() {
        var row = new NotifyMessage();
        row.setId(81L); row.setTenantId(41L); row.setDeliveryStatus("PENDING"); row.setRetryCount(0);
        return row;
    }

    private static NotifySendRequest request() {
        return NotifySendRequest.builder().tenantId(41L).channel(NotifyChannel.SMS).recipientId(82L)
                .title("owned fixture").content("owned fixture").idempotencyKey("owned-key").build();
    }
}
