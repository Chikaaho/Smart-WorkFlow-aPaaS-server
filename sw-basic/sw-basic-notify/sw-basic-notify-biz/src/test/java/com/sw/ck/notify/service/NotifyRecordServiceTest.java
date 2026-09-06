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
import static org.mockito.Mockito.lenient;
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
    @DisplayName("非 FAILED 记录重发 → 条件更新零行命中 → 拒绝（并发单受理）")
    void resendRejectedWhenNotFailed() {
        NotifyMessage running = failedMessage();
        running.setDeliveryStatus("RESENDING");
        when(messageMapper.selectById(2L)).thenReturn(running);
        when(messageMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> recordService.resend(2L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("仅明确失败");
        verify(notifyFacade, never()).attemptDelivery(any());
    }

    @Test
    @DisplayName("FAILED 记录重发受理 → 投递一次、回写最新结果、追加尝试流水")
    void resendAcceptedDeliversOnceAndLogs() {
        when(messageMapper.selectById(2L)).thenReturn(failedMessage());
        when(messageMapper.update(isNull(), any())).thenReturn(1);
        lenient().when(attemptMapper.selectList(any())).thenReturn(List.of());
        when(notifyFacade.attemptDelivery(any())).thenReturn(
                NotifySendResult.builder().channel(com.sw.ck.notify.api.NotifyChannel.SMS)
                        .status("SUCCESS").externalMessageId("ext-1").build());

        String latest = recordService.resend(2L);

        assertThat(latest).isEqualTo("SUCCESS");
        verify(notifyFacade, times(1)).attemptDelivery(any());
        verify(messageMapper, times(2)).update(isNull(), any());
        ArgumentCaptor<NotifySendAttempt> captor = ArgumentCaptor.forClass(NotifySendAttempt.class);
        verify(attemptMapper).insert(captor.capture());
        assertThat(captor.getValue().getAttemptNo()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
    }
}
