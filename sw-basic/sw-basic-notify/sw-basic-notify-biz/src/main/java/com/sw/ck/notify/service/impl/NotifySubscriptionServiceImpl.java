package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.dto.NotifySubscriptionSaveReq;
import com.sw.ck.notify.dto.NotifySubscriptionView;
import com.sw.ck.notify.entity.NotifyRule;
import com.sw.ck.notify.entity.NotifySubscription;
import com.sw.ck.notify.mapper.NotifyRuleMapper;
import com.sw.ck.notify.mapper.NotifySubscriptionMapper;
import com.sw.ck.notify.service.NotifySubscriptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户订阅偏好服务实现（I6）。 */
@Service
public class NotifySubscriptionServiceImpl implements NotifySubscriptionService {

    private final NotifySubscriptionMapper subscriptionMapper;
    private final NotifyRuleMapper ruleMapper;
    private final com.sw.ck.notify.mapper.NotifyChannelConfigMapper channelConfigMapper;

    public NotifySubscriptionServiceImpl(NotifySubscriptionMapper subscriptionMapper,
                                         NotifyRuleMapper ruleMapper,
                                         com.sw.ck.notify.mapper.NotifyChannelConfigMapper channelConfigMapper) {
        this.subscriptionMapper = subscriptionMapper;
        this.ruleMapper = ruleMapper;
        this.channelConfigMapper = channelConfigMapper;
    }

    @Override
    public List<NotifySubscriptionView> preferences(Long userId) {
        return subscriptionMapper.selectList(
                        Wrappers.<NotifySubscription>lambdaQuery().eq(NotifySubscription::getUserId, userId))
                .stream().map(s -> {
                    NotifySubscriptionView v = new NotifySubscriptionView();
                    v.setEventType(s.getEventType());
                    v.setChannel(s.getChannel());
                    v.setEnabled(Boolean.TRUE.equals(s.getEnabled()));
                    return v;
                }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Long userId, NotifySubscriptionSaveReq req) {
        if (req == null || req.getItems() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "订阅内容不能为空");
        }
        for (NotifySubscriptionSaveReq.Item item : req.getItems()) {
            if (item == null || !StringUtils.hasText(item.getEventType()) || !StringUtils.hasText(item.getChannel())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "订阅项不完整");
            }
            parseChannel(item.getChannel());
            if (!canOptOut(item.getEventType(), item.getChannel()) && Boolean.FALSE.equals(item.getEnabled())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                        "必须送达事件/渠道不允许关闭: " + item.getEventType() + "/" + item.getChannel());
            }
        }
        subscriptionMapper.delete(Wrappers.<NotifySubscription>lambdaQuery()
                .eq(NotifySubscription::getUserId, userId));
        for (NotifySubscriptionSaveReq.Item item : req.getItems()) {
            NotifySubscription s = new NotifySubscription();
            s.setUserId(userId);
            s.setEventType(item.getEventType());
            s.setChannel(item.getChannel());
            s.setEnabled(!Boolean.FALSE.equals(item.getEnabled()));
            subscriptionMapper.insert(s);
        }
    }

    @Override
    public boolean canOptOut(String eventType, String channel) {
        List<NotifyRule> rules = ruleMapper.selectList(Wrappers.<NotifyRule>lambdaQuery()
                .eq(NotifyRule::getEventType, eventType)
                .eq(NotifyRule::getEnabled, true));
        // 必须送达规则（required=1）存在时，该规则渠道链中的站内信不可被关闭
        for (NotifyRule r : rules) {
            if (r.getRequiredFlag() == null || r.getRequiredFlag() != 1) {
                continue;
            }
            String first = r.getChannelPriority() == null ? "IN_APP"
                    : r.getChannelPriority().split(",")[0].trim().toUpperCase(Locale.ROOT);
            if ("IN_APP".equalsIgnoreCase(channel) && "IN_APP".equals(first)) {
                return false;
            }
        }
        return true;
    }

    private NotifyChannel parseChannel(String raw) {
        try {
            return NotifyChannel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "未知渠道: " + raw);
        }
    }
}
