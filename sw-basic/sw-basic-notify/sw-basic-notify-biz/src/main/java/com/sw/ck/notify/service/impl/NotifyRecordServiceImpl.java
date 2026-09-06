package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifySendAttempt;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.mapper.NotifySendAttemptMapper;
import com.sw.ck.notify.service.NotifyRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通知发送记录服务实现。 */
@Service
public class NotifyRecordServiceImpl implements NotifyRecordService {

    private static final Logger log = LoggerFactory.getLogger(NotifyRecordServiceImpl.class);

    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RESENDING = "RESENDING";

    private final NotifyMessageMapper messageMapper;
    private final NotifySendAttemptMapper attemptMapper;
    private final NotifyFacade notifyFacade;

    public NotifyRecordServiceImpl(NotifyMessageMapper messageMapper,
                                   NotifySendAttemptMapper attemptMapper,
                                   NotifyFacade notifyFacade) {
        this.messageMapper = messageMapper;
        this.attemptMapper = attemptMapper;
        this.notifyFacade = notifyFacade;
    }

    @Override
    public PageResult<NotifyMessage> pageRecords(PageParam pageParam, String deliveryStatus, Long recipientId,
                                                 String keyword, LocalDateTime timeFrom, LocalDateTime timeTo) {
        LambdaQueryWrapper<NotifyMessage> wrapper = Wrappers.lambdaQuery(NotifyMessage.class)
                .eq(deliveryStatus != null && !deliveryStatus.isBlank(), NotifyMessage::getDeliveryStatus, deliveryStatus)
                .eq(recipientId != null, NotifyMessage::getRecipientId, recipientId)
                .ge(timeFrom != null, NotifyMessage::getCreateTime, timeFrom)
                .le(timeTo != null, NotifyMessage::getCreateTime, timeTo)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(NotifyMessage::getTitle, keyword.trim())
                        .or().like(NotifyMessage::getContent, keyword.trim())
                        .or().like(NotifyMessage::getBizId, keyword.trim()))
                .orderByDesc(NotifyMessage::getCreateTime);
        PageResult<NotifyMessage> page = messageMapper.selectPage(pageParam, wrapper);
        return page;
    }

    @Override
    public Map<String, Object> recordDetail(Long id) {
        NotifyMessage message = messageMapper.selectById(id);
        if (message == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "发送记录不存在");
        }
        List<NotifySendAttempt> attempts = attemptMapper.selectList(
                Wrappers.lambdaQuery(NotifySendAttempt.class)
                        .eq(NotifySendAttempt::getMessageId, id)
                        .orderByAsc(NotifySendAttempt::getAttemptNo));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        detail.put("attempts", attempts);
        return detail;
    }

    @Override
    @Transactional
    public String resend(Long id) {
        NotifyMessage message = messageMapper.selectById(id);
        if (message == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "发送记录不存在");
        }
        // 并发单受理：仅 FAILED → RESENDING 的条件更新命中一行才视为受理成功
        int accepted = messageMapper.update(null,
                Wrappers.<NotifyMessage>lambdaUpdate()
                        .set(NotifyMessage::getDeliveryStatus, STATUS_RESENDING)
                        .eq(NotifyMessage::getId, id)
                        .eq(NotifyMessage::getDeliveryStatus, STATUS_FAILED));
        if (accepted == 0) {
            String current = messageMapper.selectById(id) == null ? null
                    : messageMapper.selectById(id).getDeliveryStatus();
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "记录当前状态为 " + current + "，仅明确失败且无进行中重发的记录可重发");
        }

        // 重建投递请求（站内信失败记录同样可重发：重投一次站内消息内容）
        NotifyChannel channel = parseChannel(message.getChannel());
        NotifySendRequest request = NotifySendRequest.builder()
                .channel(channel)
                .recipientId(message.getRecipientId())
                .title(message.getTitle())
                .content(message.getContent())
                .bizType(parseBizType(message.getBizType()))
                .bizId(message.getBizId())
                .tenantId(message.getTenantId())
                .build();

        int attemptNo = nextAttemptNo(id);
        NotifySendResult result = notifyFacade.attemptDelivery(request);
        String latest = result.getStatus() == null ? STATUS_FAILED : result.getStatus();

        // 回写最新结果 + 追加尝试流水
        messageMapper.update(null, Wrappers.<NotifyMessage>lambdaUpdate()
                .set(NotifyMessage::getDeliveryStatus, latest)
                .set(NotifyMessage::getFailureReason, result.getFailureReason())
                .set(NotifyMessage::getExternalMessageId, result.getExternalMessageId())
                .eq(NotifyMessage::getId, id));
        NotifySendAttempt attempt = new NotifySendAttempt();
        attempt.setMessageId(id);
        attempt.setAttemptNo(attemptNo);
        attempt.setChannel(result.getChannel() == null ? message.getChannel() : result.getChannel().name());
        attempt.setStatus(latest);
        attempt.setFailureReason(result.getFailureReason());
        attempt.setExternalMessageId(result.getExternalMessageId());
        attemptMapper.insert(attempt);
        log.info("通知重发完成: id={}, attemptNo={}, result={}", id, attemptNo, latest);
        return latest;
    }

    // ==================== 内部方法 ====================

    private int nextAttemptNo(Long messageId) {
        List<NotifySendAttempt> attempts = attemptMapper.selectList(
                Wrappers.lambdaQuery(NotifySendAttempt.class)
                        .eq(NotifySendAttempt::getMessageId, messageId));
        return attempts.stream().mapToInt(a -> a.getAttemptNo() == null ? 0 : a.getAttemptNo())
                .max().orElse(0) + 1;
    }

    private NotifyChannel parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return NotifyChannel.IN_APP;
        }
        try {
            return NotifyChannel.valueOf(channel);
        } catch (IllegalArgumentException e) {
            return NotifyChannel.IN_APP;
        }
    }

    private NotifyBizType parseBizType(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            return NotifyBizType.SYSTEM;
        }
        try {
            return NotifyBizType.valueOf(bizType);
        } catch (IllegalArgumentException e) {
            return NotifyBizType.SYSTEM;
        }
    }
}
