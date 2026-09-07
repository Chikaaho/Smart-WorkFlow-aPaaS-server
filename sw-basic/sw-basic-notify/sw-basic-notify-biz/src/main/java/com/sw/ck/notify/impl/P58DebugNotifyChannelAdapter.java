package com.sw.ck.notify.impl;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** P58 开发验收用独立渠道适配器；生产 profile 不装配，不伪造真实第三方投递。 */
@Component
@Profile("dev")
public class P58DebugNotifyChannelAdapter implements NotifyChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(P58DebugNotifyChannelAdapter.class);
    private static final AtomicInteger INVOCATION_COUNT = new AtomicInteger();

    @Override
    public NotifyChannel channel() {
        return NotifyChannel.SMS;
    }

    @Override
    public NotifySendResult send(NotifySendRequest request) {
        String marker = (request.getIdempotencyKey() + " " + request.getTitle() + " " + request.getContent())
                .toUpperCase();
        NotifySendResult result;
        if (marker.contains("TIMEOUT")) {
            result = NotifySendResult.builder().channel(NotifyChannel.SMS).status("TIMEOUT")
                    .failureReason("P58 adapter timeout").build();
        } else if (marker.contains("FAILURE")) {
            result = NotifySendResult.builder().channel(NotifyChannel.SMS).status("FAILED")
                    .failureReason("P58 adapter failure").build();
        } else {
            String externalId = "p58_ext_" + Integer.toHexString(String.valueOf(request.getIdempotencyKey()).hashCode());
            result = NotifySendResult.builder().channel(NotifyChannel.SMS).status("SUCCESS")
                    .externalMessageId(externalId).build();
        }
        int callNo = INVOCATION_COUNT.incrementAndGet();
        log.info("P58_DEBUG_ADAPTER callNo={} channel={} key={} status={} failureReason={}",
                callNo, NotifyChannel.SMS, request.getIdempotencyKey(), result.getStatus(), result.getFailureReason());
        return result;
    }
}
