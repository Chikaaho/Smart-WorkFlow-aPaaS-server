package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.notify.dto.NotifyRuleDTO;
import com.sw.ck.notify.dto.NotifyRuleQuery;
import com.sw.ck.notify.entity.NotifyRule;
import com.sw.ck.notify.mapper.NotifyRuleMapper;
import com.sw.ck.notify.service.NotifyRuleService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** 通知规则服务实现（I6）。 */
@Service
public class NotifyRuleServiceImpl implements NotifyRuleService {

    private static final int MAX_CHANNEL_PRIORITY = 200;
    /** 接收人规则只允许受控形式：冒号键控 / 语义角色，不接受自由文本 */
    private static final String RECIPIENT_RULE_TOKEN =
            "(INITIATOR|ASSIGNEE|ALL|ROLE:[A-Za-z0-9_-]+|DEPT:[0-9]+|USER:[0-9]+)";

    private final NotifyRuleMapper ruleMapper;

    public NotifyRuleServiceImpl(NotifyRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    @Override
    public PageResult<NotifyRuleDTO> pageRules(NotifyRuleQuery query) {
        LambdaQueryWrapper<NotifyRule> w = Wrappers.lambdaQuery(NotifyRule.class);
        if (StringUtils.hasText(query.getEventType())) {
            w.eq(NotifyRule::getEventType, query.getEventType());
        }
        if (query.getEnabled() != null) {
            w.eq(NotifyRule::getEnabled, Boolean.TRUE.equals(query.getEnabled()) ? 1 : 0);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            w.and(x -> x.like(NotifyRule::getRuleCode, kw).or().like(NotifyRule::getName, kw));
        }
        w.orderByDesc(NotifyRule::getId);
        Page<NotifyRule> resultPage = ruleMapper.selectPage(Page.of(
                query.getPageNum() == null ? 1 : Math.max(1, query.getPageNum()),
                Math.min(Math.max(1, query.getPageSize() == null ? 20 : query.getPageSize()), 200)), w);
        Page<NotifyRuleDTO> converted = Page.of(resultPage.getCurrent(), resultPage.getSize(),
                resultPage.getTotal());
        converted.setRecords(resultPage.getRecords().stream().map(this::toDTO).toList());
        return PageResult.of(converted);
    }

    private NotifyRuleDTO toDTO(NotifyRule r) {
        NotifyRuleDTO dto = new NotifyRuleDTO();
        dto.setId(r.getId());
        dto.setRuleCode(r.getRuleCode());
        dto.setName(r.getName());
        dto.setEventType(r.getEventType());
        dto.setChannelPriority(r.getChannelPriority());
        dto.setRecipientRule(r.getRecipientRule());
        dto.setRequiredFlag(r.getRequiredFlag() != null && r.getRequiredFlag() == 1);
        dto.setFailurePolicy(r.getFailurePolicy());
        dto.setEnabled(r.getEnabled() != null && r.getEnabled() == 1);
        dto.setRemark(r.getRemark());
        return dto;
    }

    @Override
    public NotifyRuleDTO getRule(Long id) {
        return toDTO(requireEntity(id));
    }

    @Override
    public Long createRule(NotifyRuleDTO dto) {
        validate(dto);
        requireCodeAvailable(dto.getRuleCode());
        NotifyRule entity = toEntity(dto);
        ruleMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void updateRule(Long id, NotifyRuleDTO dto) {
        NotifyRule existing = requireEntity(id);
        validate(dto);
        if (!existing.getRuleCode().equals(dto.getRuleCode())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "规则编码不可变更");
        }
        NotifyRule entity = toEntity(dto);
        entity.setId(id);
        ruleMapper.updateById(entity);
    }

    @Override
    public void deleteRule(Long id) {
        requireEntity(id);
        ruleMapper.deleteById(id);
    }

    @Override
    public void toggleRule(Long id, boolean enabled) {
        NotifyRule entity = requireEntity(id);
        entity.setEnabled(enabled ? 1 : 0);
        ruleMapper.updateById(entity);
    }

    @Override
    public List<NotifyRule> listEnabledByEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return List.of();
        }
        return ruleMapper.selectList(Wrappers.<NotifyRule>lambdaQuery()
                .eq(NotifyRule::getEventType, eventType)
                .eq(NotifyRule::getEnabled, 1)
                .orderByAsc(NotifyRule::getId));
    }

    // ==================== 内部 ====================

    void validate(NotifyRuleDTO dto) {
        if (dto.getRuleCode() == null || !dto.getRuleCode().matches("[A-Za-z][A-Za-z0-9_]{1,98}")) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "规则编码非法");
        }
        if (!StringUtils.hasText(dto.getName()) || !StringUtils.hasText(dto.getEventType())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "规则名称与事件类型不能为空");
        }
        if (!StringUtils.hasText(dto.getChannelPriority())
                || dto.getChannelPriority().length() > MAX_CHANNEL_PRIORITY) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "渠道顺序不能为空且不得超过 200 字符");
        }
        for (String token : dto.getChannelPriority().split(",")) {
            String trimmed = token.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                com.sw.ck.notify.api.NotifyChannel.valueOf(trimmed);
            } catch (IllegalArgumentException e) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "渠道顺序含未知渠道: " + token);
            }
        }
        // 渠道顺序必须以 IN_APP 开头：必须送达规则的最低保证（方向 §3.4/§3.6）
        if (!"IN_APP".equalsIgnoreCase(dto.getChannelPriority().split(",")[0].trim())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "渠道顺序必须以 IN_APP 开头（站内信保底）");
        }
        String rr = dto.getRecipientRule();
        if (!StringUtils.hasText(rr)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "接收人规则不能为空");
        }
        for (String token : rr.split(";")) {
            if (!token.matches("^" + RECIPIENT_RULE_TOKEN + "$")) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "接收人规则含非法形式: " + token);
            }
        }
    }

    private NotifyRule toEntity(NotifyRuleDTO dto) {
        NotifyRule r = new NotifyRule();
        r.setRuleCode(dto.getRuleCode());
        r.setName(dto.getName());
        r.setEventType(dto.getEventType());
        r.setChannelPriority(dto.getChannelPriority());
        r.setRecipientRule(dto.getRecipientRule());
        r.setRequiredFlag(Boolean.TRUE.equals(dto.getRequiredFlag()) ? 1 : 0);
        r.setFailurePolicy("MANUAL".equals(dto.getFailurePolicy()) ? "MANUAL" : "RETRY");
        r.setEnabled(dto.getEnabled() == null || Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        r.setRemark(dto.getRemark());
        return r;
    }

    private NotifyRule requireEntity(Long id) {
        NotifyRule r = ruleMapper.selectById(id);
        if (r == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "通知规则不存在");
        }
        return r;
    }

    private void requireCodeAvailable(String ruleCode) {
        Long count = ruleMapper.selectCount(Wrappers.<NotifyRule>lambdaQuery()
                .eq(NotifyRule::getRuleCode, ruleCode));
        if (count != null && count > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "规则编码已存在: " + ruleCode);
        }
    }
}
