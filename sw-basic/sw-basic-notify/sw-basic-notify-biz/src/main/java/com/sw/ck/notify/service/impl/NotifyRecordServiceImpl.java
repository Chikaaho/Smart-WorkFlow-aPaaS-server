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

import java.time.Duration;
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
    /** 尝试流水中的在途标记：投递已发出但尚未回写终态；滞留即上次重发中断，可接管重发 */
    private static final String ATTEMPT_IN_FLIGHT = "DELIVERING";
    /** 最小重发间隔：同步重发完成后记录回到 FAILED，节流窗口内拒绝快速重复重发（防重复业务效果） */
    private static final Duration MIN_RESEND_INTERVAL = Duration.ofSeconds(10);

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

    /**
     * 重发（单节点同步执行，方法级串行保证并发单受理）。
     * 可受理：FAILED；或 RESENDING 且最新尝试流水为在途标记（上次重发中断的滞留记录，接管恢复）。
     * 投递前先落在途尝试流水；投递异常回写 FAILED，进程中断后滞留状态与在途流水可观察、可接管。
     */
    @Override
    public synchronized String resend(Long id) {
        NotifyMessage message = messageMapper.selectById(id);
        if (message == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "发送记录不存在");
        }
        String current = message.getDeliveryStatus();
        boolean takeover = false;
        // 节流：仅针对重发尝试（attemptNo>=2；attemptNo=1 为原始发送流水，不得拦截首次合法重发）。
        // 最近一次重发在最小间隔内 → 拒绝，保证窗口内并发/连点只产生一次真实重发。
        NotifySendAttempt lastAny = latestAttempt(id);
        if (lastAny != null && lastAny.getCreateTime() != null
                && lastAny.getAttemptNo() != null && lastAny.getAttemptNo() >= 2) {
            Duration since = Duration.between(lastAny.getCreateTime(), LocalDateTime.now());
            if (since.compareTo(MIN_RESEND_INTERVAL) < 0 && !ATTEMPT_IN_FLIGHT.equals(lastAny.getStatus())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                        "重发过于频繁，请约 " + Math.max(1, MIN_RESEND_INTERVAL.minus(since).toSeconds())
                                + " 秒后再试");
            }
        }
        if (STATUS_RESENDING.equals(current)) {
            NotifySendAttempt latest = latestAttempt(id);
            if (latest == null || !ATTEMPT_IN_FLIGHT.equals(latest.getStatus())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                        "记录当前状态为 " + current + "，仅明确失败且无进行中重发的记录可重发");
            }
            takeover = true;
        } else if (!STATUS_FAILED.equals(current)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "记录当前状态为 " + current + "，仅明确失败且无进行中重发的记录可重发");
        }
        // 条件更新兜底并发：FAILED→RESENDING，或接管时 RESENDING 原位确认
        int accepted = messageMapper.update(null,
                Wrappers.<NotifyMessage>lambdaUpdate()
                        .set(NotifyMessage::getDeliveryStatus, STATUS_RESENDING)
                        .eq(NotifyMessage::getId, id)
                        .eq(NotifyMessage::getDeliveryStatus, current));
        if (accepted == 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "记录状态已变化，仅明确失败且无进行中重发的记录可重发");
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
        // 投递前先落在途流水：中断后可观察、可接管
        NotifySendAttempt inFlight = new NotifySendAttempt();
        inFlight.setMessageId(id);
        inFlight.setAttemptNo(attemptNo);
        inFlight.setChannel(message.getChannel());
        inFlight.setStatus(ATTEMPT_IN_FLIGHT);
        attemptMapper.insert(inFlight);

        NotifySendResult result;
        try {
            result = notifyFacade.attemptDelivery(request);
        } catch (Exception e) {
            log.warn("通知重发投递异常，按失败回写: id={}, attemptNo={}", id, attemptNo, e);
            result = NotifySendResult.builder()
                    .channel(parseChannel(message.getChannel()))
                    .status(STATUS_FAILED)
                    .failureReason("重发投递异常: " + e.getMessage())
                    .build();
        }
        String latest = result.getStatus() == null ? STATUS_FAILED : result.getStatus();

        // 回写最新结果 + 终态化本次尝试流水
        messageMapper.update(null, Wrappers.<NotifyMessage>lambdaUpdate()
                .set(NotifyMessage::getDeliveryStatus, latest)
                .set(NotifyMessage::getFailureReason, result.getFailureReason())
                .set(NotifyMessage::getExternalMessageId, result.getExternalMessageId())
                .eq(NotifyMessage::getId, id));
        attemptMapper.update(null, Wrappers.<NotifySendAttempt>lambdaUpdate()
                .set(NotifySendAttempt::getStatus, latest)
                .set(NotifySendAttempt::getFailureReason, result.getFailureReason())
                .set(NotifySendAttempt::getExternalMessageId, result.getExternalMessageId())
                .eq(NotifySendAttempt::getMessageId, id)
                .eq(NotifySendAttempt::getAttemptNo, attemptNo));
        log.info("通知重发完成: id={}, attemptNo={}, takeover={}, result={}", id, attemptNo, takeover, latest);
        return latest;
    }

    // ==================== 内部方法 ====================

    private int nextAttemptNo(Long messageId) {
        return latestAttempt(messageId) == null ? 1
                : latestAttempt(messageId).getAttemptNo() + 1;
    }

    private NotifySendAttempt latestAttempt(Long messageId) {
        List<NotifySendAttempt> attempts = attemptMapper.selectList(
                Wrappers.lambdaQuery(NotifySendAttempt.class)
                        .eq(NotifySendAttempt::getMessageId, messageId)
                        .orderByDesc(NotifySendAttempt::getAttemptNo)
                        .last("LIMIT 1"));
        return attempts.isEmpty() ? null : attempts.get(0);
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
