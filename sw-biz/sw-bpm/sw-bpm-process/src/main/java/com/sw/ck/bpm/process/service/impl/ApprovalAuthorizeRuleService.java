package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmAuthorizeRule;
import com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.time.LocalDateTime;
import java.util.List;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 授权代理规则管理执行（自 ApprovalLifecycleServiceImpl 拆出，行为不变）：
 * 创建/撤销/查询本人代理规则，冲突生效期拒绝与循环代理拒绝。
 * 事务边界保留在 {@link ApprovalLifecycleServiceImpl} 门面的公开方法上。
 */
@Service
@Log4j2
class ApprovalAuthorizeRuleService {

    @Autowired
    private BpmAuthorizeRuleMapper authorizeRuleMapper;
    @Autowired
    private ApprovalLifecycleSupport support;

    Long saveAuthorizeRule(ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        Long principal = actor.getUserId();
        Long agent = request.getTargetUserId();
        if (agent == null || principal.equals(agent)) {
            throw new BaseException(BpmErrorCode.ACTION_SELF_INVALID);
        }
        support.requireActiveUser(agent, actor.getTenantId());
        LocalDateTime start = support.parseTime(request.getStartAt(),
                BpmErrorCode.AUTHORIZATION_INVALID);
        LocalDateTime end = support.parseTime(request.getEndAt(), BpmErrorCode.AUTHORIZATION_INVALID);
        if (start != null && end != null && !start.isBefore(end)) {
            throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(), "生效区间不合法");
        }
        String scopeType = support.nullSafe(request.getScopeType(), "GLOBAL");
        if (!List.of("GLOBAL", "PROCESS", "NODE", "BUSINESS").contains(scopeType)) {
            throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(), "范围类型不合法");
        }

        // 冲突拒绝：同一 principal + 同范围 + 重叠生效期的 ACTIVE 规则只保留一条
        List<BpmAuthorizeRule> existing = authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, principal)
                        .eq(BpmAuthorizeRule::getAgentId, agent)
                        .eq(BpmAuthorizeRule::getScopeType, scopeType)
                        .eq(BpmAuthorizeRule::getStatus, "ACTIVE"));
        for (BpmAuthorizeRule item : existing) {
            if (overlaps(item.getStartAt(), item.getEndAt(), start, end)) {
                throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(),
                        "存在冲突的生效期间规则");
            }
        }
        // 循环代理拒绝：agent 的生效规则又指回 principal（A→B、B→A）
        List<BpmAuthorizeRule> agentRules = authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, agent)
                        .eq(BpmAuthorizeRule::getStatus, "ACTIVE"));
        for (BpmAuthorizeRule item : agentRules) {
            if (principal.equals(item.getAgentId())) {
                throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(),
                        "授权形成循环代理");
            }
        }

        BpmAuthorizeRule rule = new BpmAuthorizeRule();
        rule.setPrincipalId(principal);
        rule.setAgentId(agent);
        rule.setScopeType(scopeType);
        rule.setProcessDefKey(blankToNull(request.getProcessDefKey()));
        rule.setNodeKey(blankToNull(request.getNodeKey()));
        rule.setBusinessKey(blankToNull(request.getBusinessKey()));
        rule.setConditionJson(request.getConditionJson() == null ? "{}" : request.getConditionJson());
        rule.setStartAt(start);
        rule.setEndAt(end);
        rule.setStatus("ACTIVE");
        rule.setDetail(support.nullSafe(request.getReason()));
        authorizeRuleMapper.insert(rule);
        log.info("代理规则已创建: principal={}, agent={}, scope={}", principal, agent, scopeType);
        return rule.getId();
    }

    void revokeAuthorizeRule(Long ruleId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmAuthorizeRule rule = authorizeRuleMapper.selectById(ruleId);
        if (rule == null) {
            return;
        }
        if (!rule.getPrincipalId().equals(actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅授权主体可撤销");
        }
        BpmAuthorizeRule patch = new BpmAuthorizeRule();
        patch.setId(rule.getId());
        patch.setStatus("REVOKED");
        authorizeRuleMapper.updateById(patch);
        log.info("代理规则已撤销: ruleId={}", ruleId);
    }

    List<BpmAuthorizeRule> listAuthorizeRules() {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null) {
            return List.of();
        }
        return authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, actor.getUserId())
                        .orderByDesc(BpmAuthorizeRule::getUpdateTime));
    }

    private boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                             LocalDateTime bStart, LocalDateTime bEnd) {
        LocalDateTime as = aStart == null ? LocalDateTime.MIN : aStart;
        LocalDateTime ae = aEnd == null ? LocalDateTime.MAX : aEnd;
        LocalDateTime bs = bStart == null ? LocalDateTime.MIN : bStart;
        LocalDateTime be = bEnd == null ? LocalDateTime.MAX : bEnd;
        return bs.isBefore(ae) && as.isBefore(be);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
