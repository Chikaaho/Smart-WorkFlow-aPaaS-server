package com.sw.ck.notify.impl;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.log4j.Log4j2;
/** P58 开发验收用独立渠道适配器；生产 profile 不装配，不伪造真实第三方投递。 */
@Component
@Profile("dev")
@Log4j2
public class P58DebugNotifyChannelAdapter implements NotifyChannelAdapter {

    private static final AtomicInteger INVOCATION_COUNT = new AtomicInteger();
    private static final NotifyChannel CHANNEL = NotifyChannel.SMS;

    @Override
    public Optional<NotifyChannel> channel() {
        return Optional.of(CHANNEL);
    }

    @Override
    public Optional<NotifySendResult> send(NotifySendRequest request) {
        String marker = (request.getIdempotencyKey() + " " + request.getTitle() + " " + request.getContent())
                .toUpperCase();
        NotifySendResult result;
        if (marker.contains("TIMEOUT")) {
            result = NotifySendResult.builder().channel(CHANNEL).status("TIMEOUT")
                    .failureReason("P58 adapter timeout").build();
        } else if (marker.contains("FAILURE")) {
            result = NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("P58 adapter failure").build();
        } else {
            String externalId = "p58_ext_" + Integer.toHexString(String.valueOf(request.getIdempotencyKey()).hashCode());
            result = NotifySendResult.builder().channel(CHANNEL).status("SUCCESS")
                    .externalMessageId(externalId).build();
        }
        int callNo = INVOCATION_COUNT.incrementAndGet();
        log.info("P58_DEBUG_ADAPTER callNo={} channel={} key={} status={} failureReason={}",
                callNo, CHANNEL, request.getIdempotencyKey(), result.getStatus(), result.getFailureReason());
        return Optional.of(result);
    }
}
