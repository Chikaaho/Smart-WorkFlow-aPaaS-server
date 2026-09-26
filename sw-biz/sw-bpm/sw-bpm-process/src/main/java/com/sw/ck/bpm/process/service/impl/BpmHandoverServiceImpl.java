package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmAuthorizeRule;
import com.sw.ck.bpm.process.entity.BpmHandover;
import com.sw.ck.bpm.process.entity.BpmHandoverItem;
import com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper;
import com.sw.ck.bpm.process.mapper.BpmHandoverItemMapper;
import com.sw.ck.bpm.process.mapper.BpmHandoverMapper;
import com.sw.ck.bpm.process.service.BpmHandoverService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 流程交接实现（I4 §3.6）。
 * <p>
 * 逐项校验：来源与目标有效且不同、目标用户同租户且启用、任务属于来源用户且未完成、
 * 流程范围匹配。每次交接生成可复核清单（BpmHandoverItem）；历史办理人与意见零改写；
 * 失败项可安全重试——重试时已迁给目标用户的任务记 SKIPPED_ALREADY_MIGRATED，不重复迁移。
 * </p>
 */
@Service
public class BpmHandoverServiceImpl implements BpmHandoverService {

    private static final Logger log = LoggerFactory.getLogger(BpmHandoverServiceImpl.class);

    private final BpmHandoverMapper handoverMapper;
    private final BpmHandoverItemMapper itemMapper;
    private final BpmAuthorizeRuleMapper authorizeRuleMapper;
    private final BpmTaskFacade bpmTaskFacade;
    private final UserQueryFacade userQueryFacade;

    public BpmHandoverServiceImpl(BpmHandoverMapper handoverMapper,
                                  BpmHandoverItemMapper itemMapper,
                                  BpmAuthorizeRuleMapper authorizeRuleMapper,
                                  BpmTaskFacade bpmTaskFacade,
                                  UserQueryFacade userQueryFacade) {
        this.handoverMapper = handoverMapper;
        this.itemMapper = itemMapper;
        this.authorizeRuleMapper = authorizeRuleMapper;
        this.bpmTaskFacade = bpmTaskFacade;
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    @Transactional
    public BpmHandover handover(Long fromUserId, Long toUserId, List<String> scopeDefKeys,
                                boolean includeProxyRules) {
        Long operator = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId();
        if (operator == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        Long tenantId = LoginUserHolder.get().getTenantId();
        if (fromUserId == null || toUserId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "来源与目标用户不能为空");
        }
        if (fromUserId.equals(toUserId)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "来源与目标用户不能相同");
        }
        // 目标必须为同租户有效用户
        // empty = 查询对象/租户上下文缺失：无法确认有效性，按原有“无效目标用户”拒绝
        if (!userQueryFacade.findActiveUserIds(List.of(toUserId), tenantId)
                .orElse(List.of()).contains(toUserId)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "目标用户无效、已停用或不属于当前租户");
        }
        Set<String> scope = scopeDefKeys == null ? Set.of()
                : new HashSet<>(scopeDefKeys.stream().filter(key -> key != null && !key.isBlank()).toList());

        BpmHandover handover = new BpmHandover();
        handover.setFromUserId(fromUserId);
        handover.setToUserId(toUserId);
        handover.setScopeDefKeys(String.join(",", scope));
        handover.setIncludeProxyRules(includeProxyRules);
        handover.setOperatorId(operator);
        handover.setExecutedAt(LocalDateTime.now());
        handover.setStatus("PROCESSING"); // 批次落库即有状态；结束时按迁移/失败结果改写
        handoverMapper.insert(handover);

        int migrated = 0;
        int failed = 0;
        int total = 0;
        String tenant = String.valueOf(tenantId);

        // 1) 未完成可办理任务迁移（稳定任务标识 + 前后责任人 + 结果/原因逐项落库）
        // empty = 租户/处理人上下文缺失：无待迁移任务（原空列表口径）
        for (BpmTaskDTO task : bpmTaskFacade.queryTodo(tenant, String.valueOf(fromUserId))
                .orElse(List.of())) {
            if (!scope.isEmpty() && !scope.contains(task.getProcessDefinitionKey())) {
                continue;
            }
            total++;
            BpmHandoverItem item = baseItem(handover.getId(), "TASK");
            item.setTaskId(task.getTaskId());
            item.setProcessInstanceId(task.getProcessInstanceId());
            item.setBeforeAssignee(fromUserId);
            item.setAfterAssignee(toUserId);
            try {
                if (toUserId.toString().equals(task.getAssignee())) {
                    // 重试安全：上一次已成功迁移的任务不再重复迁移
                    item.setResult("SKIPPED_ALREADY_MIGRATED");
                } else if (!fromUserId.toString().equals(task.getAssignee())) {
                    item.setResult("FAILED");
                    item.setFailReason("任务当前办理人已变化: " + task.getAssignee());
                    failed++;
                } else {
                    // present = APPLIED；任务不存在/已被处理继续抛原异常（由下方逐项失败兜住）
                    bpmTaskFacade.setAssignee(task.getTaskId(), String.valueOf(toUserId))
                    .orElseThrow(() -> new IllegalStateException(
                            "BpmTaskFacade#setAssignee 契约恒 present，empty 属契约违约"));
                    item.setResult("MIGRATED");
                    migrated++;
                }
            } catch (RuntimeException e) {
                item.setResult("FAILED");
                item.setFailReason(e.getMessage());
                failed++;
            }
            itemMapper.insert(item);
        }

        // 2) 代理规则随迁：仅显式勾选 + 有效期内 + 双方校验；过期规则与历史不迁移
        if (includeProxyRules) {
            List<BpmAuthorizeRule> rules = authorizeRuleMapper.selectList(
                    Wrappers.<BpmAuthorizeRule>lambdaQuery()
                            .eq(BpmAuthorizeRule::getPrincipalId, fromUserId)
                            .eq(BpmAuthorizeRule::getStatus, "ACTIVE")
                            .gt(BpmAuthorizeRule::getEndAt, LocalDateTime.now()));
            for (BpmAuthorizeRule rule : rules) {
                total++;
                BpmHandoverItem item = baseItem(handover.getId(), "PROXY_RULE");
                item.setRuleId(rule.getId());
                item.setBeforeAssignee(fromUserId);
                item.setAfterAssignee(toUserId);
                try {
                    if (!scope.isEmpty() && rule.getProcessDefKey() != null
                            && !scope.contains(rule.getProcessDefKey())) {
                        item.setResult("SKIPPED");
                        item.setFailReason("代理规则流程范围不在本次交接范围");
                    } else {
                        BpmAuthorizeRule migratedRule = new BpmAuthorizeRule();
                        migratedRule.setPrincipalId(toUserId);
                        migratedRule.setAgentId(rule.getAgentId());
                        migratedRule.setScopeType(rule.getScopeType());
                        migratedRule.setProcessDefKey(rule.getProcessDefKey());
                        migratedRule.setNodeKey(rule.getNodeKey());
                        migratedRule.setBusinessKey(rule.getBusinessKey());
                        migratedRule.setConditionJson(rule.getConditionJson());
                        migratedRule.setStartAt(rule.getStartAt());
                        migratedRule.setEndAt(rule.getEndAt());
                        migratedRule.setStatus("ACTIVE");
                        migratedRule.setDetail("HANDOVER_FROM:" + rule.getId());
                        authorizeRuleMapper.insert(migratedRule);
                        rule.setStatus("HANDOVER_MIGRATED");
                        authorizeRuleMapper.updateById(rule);
                        item.setResult("MIGRATED");
                        migrated++;
                    }
                } catch (RuntimeException e) {
                    item.setResult("FAILED");
                    item.setFailReason(e.getMessage());
                    failed++;
                }
                itemMapper.insert(item);
            }
        }

        handover.setTotalItems(total);
        handover.setMigratedItems(migrated);
        handover.setFailedItems(failed);
        handover.setStatus(failed == 0 ? (total == 0 ? "COMPLETED" : "COMPLETED")
                : (migrated > 0 ? "PARTIAL" : "FAILED"));
        handoverMapper.updateById(handover);
        log.info("流程交接完成: handoverId={}, from={}, to={}, migrated={}, failed={}",
                handover.getId(), fromUserId, toUserId, migrated, failed);
        return handover;
    }

    @Override
    public List<BpmHandoverItem> items(Long handoverId) {
        return itemMapper.selectList(Wrappers.<BpmHandoverItem>lambdaQuery()
                .eq(BpmHandoverItem::getHandoverId, handoverId)
                .orderByAsc(BpmHandoverItem::getId));
    }

    private static BpmHandoverItem baseItem(Long handoverId, String type) {
        BpmHandoverItem item = new BpmHandoverItem();
        item.setHandoverId(handoverId);
        item.setItemType(type);
        return item;
    }
}
