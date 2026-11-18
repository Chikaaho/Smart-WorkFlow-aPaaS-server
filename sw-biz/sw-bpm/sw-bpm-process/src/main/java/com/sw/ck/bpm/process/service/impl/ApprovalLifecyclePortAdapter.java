package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort;
import com.sw.ck.bpm.api.common.BatchItemStatusEnum;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmAuthorizeRule;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmConsensusVote;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmTaskDeadline;
import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper;
import com.sw.ck.bpm.process.mapper.BpmConsensusVoteMapper;
import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
import com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.jdbc.JdbcMaps;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 引擎端口适配执行（自 ApprovalLifecycleServiceImpl 拆出，行为不变）：
 * 会签投票端口（幂等投票 + 唯一键并发兜底 + v2 轮次分支数分母）、任务进入
 * 生命周期端口（代理规则匹配改写 assignee + 办理时限登记）。端口方法由流程
 * 引擎回调调用，事务边界与拆分前一致（端口方法本身无事务注解）。
 */
@Service
@Log4j2
class ApprovalLifecyclePortAdapter {

    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private BpmInstanceService bpmInstanceService;
    @Autowired
    private BpmProcessDefService bpmProcessDefService;
    @Autowired
    private ApprovalActionService approvalActionService;
    @Autowired
    private BpmAuthorizeRuleMapper authorizeRuleMapper;
    @Autowired
    private BpmConsensusVoteMapper consensusVoteMapper;
    @Autowired
    private BpmTaskDeadlineMapper deadlineMapper;
    @Autowired
    private ParticipantSnapshotMapper participantSnapshotMapper;

    /** P63 动态分支快照（可选注入）：v2 轮次汇聚分母按分支数权威计算。 */
    @Autowired(required = false)
    private DynamicBranchSnapshotMapper dynamicBranchSnapshotMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApprovalLifecycleSupport support;

    ConsensusVotePort consensusVotePort() {
        return new ConsensusVotePort() {
            @Override
            public Optional<Boolean> record(String tenantId, String processInstanceId, String nodeKey,
                                            String taskId, String actorId, String outcome) {
                try {
                    Long actor = Long.valueOf(actorId);
                    if (tenantId == null) {
                        // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed
                        throw new IllegalArgumentException("会签投票缺少租户上下文: taskId=" + taskId);
                    }
                    Long tenant = Long.valueOf(tenantId);
                    if (consensusVoteMapper.selectCount(
                            Wrappers.<BpmConsensusVote>lambdaQuery()
                                    .eq(BpmConsensusVote::getTaskId, taskId)
                                    .eq(BpmConsensusVote::getActorId, actor)) > 0) {
                        return Optional.of(false); // 幂等：同任务同人一票
                    }
                    BpmConsensusVote vote = new BpmConsensusVote();
                    vote.setProcessInstanceId(processInstanceId);
                    vote.setNodeKey(nodeKey);
                    vote.setTaskId(taskId);
                    vote.setActorId(actor);
                    vote.setOutcome(outcome);
                    vote.setTenantId(tenant);
                    // 引擎监听线程无登录态：审计列显式兜底，不允许拦截器缺省失败
                    if (vote.getCreateTime() == null) {
                        LocalDateTime now = LocalDateTime.now();
                        vote.setCreateTime(now);
                        vote.setUpdateTime(now);
                    }
                    if (vote.getDeleted() == null) {
                        vote.setDeleted(0);
                    }
                    if (vote.getVersion() == null) {
                        vote.setVersion(0L);
                    }
                    return Optional.of(consensusVoteMapper.insert(vote) > 0);
                } catch (DuplicateKeyException duplicate) {
                    // 唯一键 uk_sw_bpm_vote_task_actor 兜底并发（多实例/重复命令）
                    return Optional.of(false);
                } catch (NumberFormatException e) {
                    // 投票人/租户标识无法解析：无法给出记录判定，不得伪装成幂等重复
                    return Optional.empty();
                }
            }

            @Override
            public Optional<Long> count(String tenantId, String processInstanceId, String nodeKey,
                                        String outcome) {
                return Optional.of(consensusVoteMapper.selectCount(
                        Wrappers.<BpmConsensusVote>lambdaQuery()
                                .eq(BpmConsensusVote::getProcessInstanceId, processInstanceId)
                                .eq(BpmConsensusVote::getNodeKey, nodeKey)
                                .eq(BpmConsensusVote::getOutcome, outcome)));
            }

            @Override
            public Optional<Long> total(String tenantId, String processInstanceId, String nodeKey) {
                try {
                    // P63 v2：分母=最新轮次有效分支数（同负责人不同部门=独立分支，DISTINCT 人数会塌缩）
                    if (dynamicBranchSnapshotMapper != null) {
                        QueryWrapper<DynamicBranchSnapshot> branchQw =
                                new QueryWrapper<>();
                        branchQw.select("MAX(round_no) AS max_round")
                                .eq("process_instance_id", processInstanceId)
                                .eq("node_key", nodeKey)
                                .eq("semantic_version", 2)
                                .eq("deleted", 0);
                        List<Map<String, Object>> roundRows =
                                JdbcMaps.caseInsensitiveKeys(
                                        dynamicBranchSnapshotMapper.selectMaps(branchQw));
                        Object maxRound = roundRows.isEmpty() ? null : roundRows.get(0).get("max_round");
                        if (maxRound instanceof Number latestRound) {
                            QueryWrapper<DynamicBranchSnapshot> countQw =
                                    new QueryWrapper<>();
                            countQw.select("COUNT(*) AS branch_total")
                                    .eq("process_instance_id", processInstanceId)
                                    .eq("node_key", nodeKey)
                                    .eq("semantic_version", 2)
                                    .eq("round_no", latestRound.longValue())
                                    .ne("status", "CANCELED")
                                    .eq("deleted", 0);
                            List<Map<String, Object>> countRows =
                                    JdbcMaps.caseInsensitiveKeys(
                                            dynamicBranchSnapshotMapper.selectMaps(countQw));
                            Object branchTotal = countRows.isEmpty() ? null
                                    : countRows.get(0).get("branch_total");
                            if (branchTotal instanceof Number branchNumber) {
                                return Optional.of(branchNumber.longValue());
                            }
                        }
                    }
                    QueryWrapper<ParticipantSnapshot> qw = new QueryWrapper<>();
                    qw.select("COUNT(DISTINCT participant_id) AS total")
                            .eq("process_instance_id", processInstanceId)
                            .eq("node_key", nodeKey)
                            .eq("deleted", 0);
                    List<Map<String, Object>> rows =
                            JdbcMaps.caseInsensitiveKeys(
                                    participantSnapshotMapper.selectMaps(qw));
                    Object total = rows.isEmpty() ? null : rows.get(0).get("total");
                    // 无冻结快照（或口径缺失）：调用方回退变量口径（原 -1 哨兵已移除）
                    return total instanceof Number number
                            ? Optional.of(number.longValue()) : Optional.empty();
                } catch (RuntimeException e) {
                    return Optional.empty(); // 端口不可用：调用方回退变量口径
                }
            }
        };
    }

    LifecycleTaskEntryPort lifecycleTaskEntryPort() {
        return new LifecycleTaskEntryPort() {
            @Override
            public Optional<String> onTaskCreate(Long tenantId, String processInstanceId, String nodeKey,
                                                 String taskId, List<String> resolvedUsers,
                                                 String nodeConfig) {
                String proxyAgent = resolveProxy(tenantId, processInstanceId, nodeKey, taskId,
                        resolvedUsers);
                registerDeadline(tenantId, processInstanceId, nodeKey, taskId, nodeConfig);
                // present = 代理改写后的 assignee；empty = 本次不改写（走默认派发）
                return Optional.ofNullable(proxyAgent);
            }

            @Override
            public Optional<List<String>> resolveParticipantsByFunction(Long tenantId,
                                                                       String processInstanceId,
                                                                       String nodeKey, String taskId,
                                                                       Map<String, Object> variables,
                                                                       String nodeConfig) {
                // 函数型参与人由配置适配器直连实现（见 BpmLifecyclePortConfiguration）
                return Optional.empty();
            }
        };
    }

    private String resolveProxy(Long tenantId, String processInstanceId, String nodeKey,
                                String taskId, List<String> resolvedUsers) {
        if (resolvedUsers == null || resolvedUsers.size() != 1) {
            return null;
        }
        String principalRaw = resolvedUsers.get(0);
        if (principalRaw == null || !principalRaw.matches("\\d+")) {
            return null;
        }
        Long principal = Long.valueOf(principalRaw);
        List<BpmAuthorizeRule> candidates = authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, principal)
                        .eq(BpmAuthorizeRule::getStatus, "ACTIVE"));
        BpmProcessDef def = findDefByInstance(processInstanceId);
        // empty = 运行期与历史均无该实例或无业务键（原 null 返回路径）：BUSINESS 作用域不命中
        Optional<String> businessKey = bpmTaskFacade.getBusinessKey(processInstanceId);
        List<BpmAuthorizeRule> matched = candidates.stream()
                .filter(this::inEffect)
                .filter(rule -> scopeMatches(rule, def == null ? null : def.getProcessKey(),
                        nodeKey, businessKey))
                .toList();
        if (matched.size() != 1) {
            if (matched.size() > 1) {
                log.warn("命中多条互斥代理规则，代理不生效（冲突拒绝）: principal={}, nodeKey={}",
                        principal, nodeKey);
            }
            return null;
        }
        BpmAuthorizeRule rule = matched.get(0);
        recordProxyJoining(processInstanceId, nodeKey, taskId, principal, rule.getAgentId(), tenantId);
        return String.valueOf(rule.getAgentId());
    }

    /** 代理接管审计：任务进入时记录 AUTHORIZE 动作与代理关系。 */
    private void recordProxyJoining(String processInstanceId, String nodeKey, String taskId,
                                    Long principal, Long agent, Long tenantId) {
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setProcessInstanceId(processInstanceId);
        record.setNodeKey(nodeKey == null ? "UNKNOWN" : nodeKey);
        record.setTaskId(taskId);
        record.setActorId(agent);
        record.setProxyForUserId(principal);
        record.setAction(ApprovalAction.AUTHORIZE.name());
        record.setDetail(support.toJson(support.mapOf("event", "PROXY_TAKEOVER")));
        record.setSettlementStatus("PROXY_JOINED");
        if (tenantId == null) {
            // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
            throw new IllegalArgumentException("代理接管审计缺少租户上下文: processInstanceId=" + processInstanceId);
        }
        record.setTenantId(tenantId);
        approvalActionService.save(record);
    }

    private boolean inEffect(BpmAuthorizeRule rule) {
        LocalDateTime now = LocalDateTime.now();
        if (rule.getStartAt() != null && rule.getStartAt().isAfter(now)) {
            return false;
        }
        return rule.getEndAt() == null || rule.getEndAt().isAfter(now);
    }

    private boolean scopeMatches(BpmAuthorizeRule rule, String processDefKey,
                                 String nodeKey, Optional<String> businessKey) {
        return switch (support.nullSafe(rule.getScopeType(), "GLOBAL")) {
            case "GLOBAL" -> true;
            case "PROCESS" -> processDefKey != null
                    && processDefKey.equals(rule.getProcessDefKey());
            case "NODE" -> processDefKey != null && processDefKey.equals(rule.getProcessDefKey())
                    && nodeKey != null && nodeKey.equals(rule.getNodeKey());
            // empty = 实例无业务键（原 null 判定）：BUSINESS 作用域不命中
            case "BUSINESS" -> businessKey
                    .filter(key -> key.equals(rule.getBusinessKey()))
                    .isPresent();
            default -> false;
        };
    }

    private void registerDeadline(Long tenantId, String processInstanceId, String nodeKey,
                                  String taskId, String nodeConfig) {
        if (nodeConfig == null || nodeConfig.isBlank()) {
            return;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(nodeConfig, Map.class);
            Object deadline = config.get("deadline");
            if (!(deadline instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> deadlineMap = (Map<String, Object>) deadline;
            LocalDateTime due = support.parseTimeSilently(String.valueOf(deadlineMap.get("dueAt")));
            if (due == null && deadlineMap.get("dueMinutes") instanceof Number minutes) {
                due = LocalDateTime.now().plusMinutes(minutes.longValue());
            }
            if (due == null) {
                throw new BaseException(BpmErrorCode.AUTO_ACTION_INVALID);
            }
            Object auto = deadlineMap.get("autoAction");
            if (auto != null
                    && !List.of("APPROVE", "DISAPPROVE", "TRANSFER").contains(String.valueOf(auto))) {
                throw new BaseException(BpmErrorCode.AUTO_ACTION_INVALID);
            }
            BpmTaskDeadline record = new BpmTaskDeadline();
            record.setProcessInstanceId(processInstanceId);
            record.setNodeKey(nodeKey);
            record.setTaskId(taskId);
            record.setDueAt(due);
            record.setRemindFired(0);
            record.setEscalationFired(0);
            record.setAutoAction(auto == null ? null : String.valueOf(auto));
            record.setAutoActionConfig(deadlineMap.get("autoActionConfig") == null
                    ? "{}" : support.toJson(deadlineMap.get("autoActionConfig")));
            record.setRunState(BatchItemStatusEnum.PENDING.getCode());
            if (tenantId == null) {
                // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
                throw new IllegalArgumentException("办理时限登记缺少租户上下文: taskId=" + taskId);
            }
            record.setTenantId(tenantId);
            deadlineMapper.insert(record);
            log.info("办理时限已登记: taskId={}, dueAt={}, autoAction={}", taskId, due, auto);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            // 时限登记失败不阻断任务创建；无时限则不扫描
            log.warn("时限登记失败（忽略）: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private BpmProcessDef findDefByInstance(String processInstanceId) {
        BpmInstance instance = bpmInstanceService
                .findByProcessInstanceId(processInstanceId).orElse(null);
        if (instance == null || instance.getProcessDefKey() == null) {
            return null;
        }
        return bpmProcessDefService.findByProcessKey(instance.getProcessDefKey());
    }
}
