package com.sw.ck.notify.service;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifySendAttempt;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.mapper.NotifySendAttemptMapper;
import com.sw.ck.notify.service.impl.NotifyRecordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知发送记录服务单测（v0.0.2 P3）。
 * 覆盖：仅 FAILED 可重发（并发单受理）；受理成功后投递、回写最新结果并追加尝试流水。
 */
@ExtendWith(MockitoExtension.class)
class NotifyRecordServiceTest {

    @Mock
    private NotifyMessageMapper messageMapper;
    @Mock
    private NotifySendAttemptMapper attemptMapper;
    @Mock
    private NotifyFacade notifyFacade;

    private NotifyRecordServiceImpl recordService;

    @BeforeEach
    void setUp() {
        recordService = new NotifyRecordServiceImpl(messageMapper, attemptMapper, notifyFacade);
    }

    private NotifyMessage failedMessage() {
        NotifyMessage msg = new NotifyMessage();
        msg.setId(2L);
        msg.setRecipientId(1L);
        msg.setTitle("短信下发");
        msg.setContent("审批超时提醒");
        msg.setBizType("SYSTEM");
        msg.setChannel("SMS");
        msg.setDeliveryStatus("FAILED");
        msg.setFailureReason("未配置生产渠道适配器");
        msg.setTenantId(1L);
        return msg;
    }

    @Test
    @DisplayName("RESENDING 且无在途流水 → 拒绝（进行中重发不可重复受理）")
    void resendRejectedWhenNotFailed() {
        NotifyMessage running = failedMessage();
        running.setDeliveryStatus("RESENDING");
        when(messageMapper.selectById(2L)).thenReturn(running);
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> recordService.resend(2L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("仅明确失败");
        verify(notifyFacade, never()).attemptDelivery(any());
    }

    @Test
    @DisplayName("FAILED 记录重发受理 → 先落在途流水，投递一次，回写最新结果并终态化流水")
    void resendAcceptedDeliversOnceAndLogs() {
        when(messageMapper.selectById(2L)).thenReturn(failedMessage());
        when(messageMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(notifyFacade.attemptDelivery(any())).thenReturn(
                NotifySendResult.builder().channel(com.sw.ck.notify.api.NotifyChannel.SMS)
                        .status("SUCCESS").externalMessageId("ext-1").build());

        String latest = recordService.resend(2L);

        assertThat(latest).isEqualTo("SUCCESS");
        verify(notifyFacade, times(1)).attemptDelivery(any());
        verify(messageMapper, times(2)).update(isNull(), any());
        ArgumentCaptor<NotifySendAttempt> insertCaptor = ArgumentCaptor.forClass(NotifySendAttempt.class);
        verify(attemptMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getAttemptNo()).isEqualTo(1);
        // 投递前流水为在途标记，投递后经 update 终态化
        assertThat(insertCaptor.getValue().getStatus()).isEqualTo("DELIVERING");
        verify(attemptMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("RESENDING 滞留且最新流水为在途标记 → 接管重发成功（中断恢复路径）")
    void takeoverStuckResending() {
        NotifyMessage stuck = failedMessage();
        stuck.setDeliveryStatus("RESENDING");
        when(messageMapper.selectById(2L)).thenReturn(stuck);
        NotifySendAttempt inFlight = new NotifySendAttempt();
        inFlight.setMessageId(2L);
        inFlight.setAttemptNo(1);
        inFlight.setStatus("DELIVERING");
        when(attemptMapper.selectList(any())).thenReturn(List.of(inFlight));
        when(messageMapper.update(isNull(), any())).thenReturn(1);
        when(notifyFacade.attemptDelivery(any())).thenReturn(
                NotifySendResult.builder().status("FAILED").failureReason("渠道仍失败").build());

        String latest = recordService.resend(2L);

        assertThat(latest).isEqualTo("FAILED");
        verify(notifyFacade, times(1)).attemptDelivery(any());
        ArgumentCaptor<NotifySendAttempt> insertCaptor = ArgumentCaptor.forClass(NotifySendAttempt.class);
        verify(attemptMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getAttemptNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("投递抛异常 → 回写 FAILED 并留失败原因，不滞留 RESENDING")
    void deliveryExceptionFinalizesFailed() {
        when(messageMapper.selectById(2L)).thenReturn(failedMessage());
        when(messageMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(notifyFacade.attemptDelivery(any())).thenThrow(new IllegalStateException("channel down"));

        String latest = recordService.resend(2L);

        assertThat(latest).isEqualTo("FAILED");
        verify(notifyFacade, times(1)).attemptDelivery(any());
        verify(messageMapper, times(2)).update(isNull(), any());
        verify(attemptMapper).update(isNull(), any());
    }
}
