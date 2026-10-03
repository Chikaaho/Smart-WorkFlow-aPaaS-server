package com.sw.ck.bpm.process.queue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.BpmCommandEffect;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmCommandEffectMapper;
import com.sw.ck.bpm.process.service.BpmCommandService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认持久化命令队列：以 {@code sw_bpm_command} 为受理/调度事实源。
 * <p>
 * enqueue 与调用方业务事务同事务（{@link Propagation#MANDATORY}，无独立事务时
 * 显式失败，避免"受理未持久化就成功返回"）；领取用条件更新保证多消费者竞争安全；
 * 业务幂等由 command_key 唯一索引 + Handler 侧幂等语义共同保证。
 * </p>
 */
@Component
public class PersistentBpmCommandQueue implements BpmCommandQueue {

    private static final Logger log = LoggerFactory.getLogger(PersistentBpmCommandQueue.class);

    /** 准入截止合法区间（P62 分级执行合同：1—300s，受理时冻结）。 */
    public static final int DEADLINE_SECONDS_MIN = 1;
    public static final int DEADLINE_SECONDS_MAX = 300;
    public static final int DEADLINE_SECONDS_DEFAULT = 30;

    private final BpmCommandService commandService;
    private final BpmCommandEffectMapper effectMapper;
    private final com.sw.ck.bpm.process.service.ResourceReleaseService releaseService;
    private final com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService;

    public PersistentBpmCommandQueue(BpmCommandService commandService,
                                     BpmCommandEffectMapper effectMapper,
                                     com.sw.ck.bpm.process.service.ResourceReleaseService releaseService,
                                     com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService) {
        this.commandService = commandService;
        this.effectMapper = effectMapper;
        this.releaseService = releaseService;
        this.admissionService = admissionService;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueue(CommandEnvelope envelope) {
        BpmCommand command = new BpmCommand();
        command.setCommandKey(envelope.getCommandKey());
        command.setCommandType(envelope.getCommandType().getCode());
        command.setChannel(envelope.getChannel().getCode());
        command.setStatus(CommandStatusEnum.PENDING.getCode());
        command.setPayload(envelope.getPayload());
        command.setRetryCount(0);
        command.setInitiatorId(envelope.getInitiatorId());
        applyTieredSemantics(command, envelope);
        applyResourceAccounting(command, envelope);
        commandService.save(command);
        envelope.setCommandId(command.getId());
        log.info("命令已受理: commandId={}, type={}, channel={}, key={}",
                command.getId(), command.getCommandType(), command.getChannel(), command.getCommandKey());
        return command.getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long requeueFailed(CommandEnvelope envelope) {
        BpmCommand command = commandService.lambdaQuery()
                .eq(BpmCommand::getTenantId, envelope.getTenantId())
                .eq(BpmCommand::getCommandKey, envelope.getCommandKey())
                .last("LIMIT 1")
                .one();
        if (command == null || !CommandStatusEnum.FAILED.getCode().equals(command.getStatus())) {
            throw new IllegalStateException(
                    "requeueFailed 仅接受已存在且 FAILED 的命令: " + envelope.getCommandKey());
        }
        command.setStatus(CommandStatusEnum.PENDING.getCode());
        command.setPayload(envelope.getPayload());
        command.setPayloadFingerprint(blankToNull(envelope.getPayloadFingerprint()));
        command.setRetryCount(0);
        command.setFailureReason(null);
        command.setNextRetryAt(null);
        command.setClaimedAt(null);
        command.setClaimToken(null);
        command.setResult(null);
        commandService.updateById(command);
        // FAILED 期间占用已释放：重新入队=重新占用（冻结字段不变，按当前上限裁决；
        // 超限则重新入队被拒、命令保持 FAILED，不突破额度。批量按剩余非终态项再占用）
        if (command.getResourceUnits() != null && command.getResourceUnits() > 0) {
            long reoccupyUnits = CommandTypeEnum.BATCH_INVOKE.getCode().equals(command.getCommandType())
                    ? releaseService.batchRemainingUnits(command.getId())
                    : command.getResourceUnits();
            command.setResourceReleasedAt(null);
            admissionService.reoccupy(command.getTenantId(), command.getResourceSegment(),
                    (int) reoccupyUnits);
            commandService.lambdaUpdate()
                    .eq(BpmCommand::getId, command.getId())
                    .eq(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                    .set(BpmCommand::getResourceReleasedAt, null)
                    .update();
        }
        envelope.setCommandId(command.getId());
        log.info("FAILED 命令已重新入队: commandId={}, key={}", command.getId(), command.getCommandKey());
        return command.getId();
    }

    @Override
    public Optional<CommandEnvelope> findByKey(Long tenantId, String commandKey) {
        BpmCommand command = commandService.lambdaQuery()
                .eq(BpmCommand::getTenantId, tenantId)
                .eq(BpmCommand::getCommandKey, commandKey)
                .last("LIMIT 1")
                .one();
        return Optional.ofNullable(command).map(this::toEnvelope);
    }

    @Override
    public List<CommandEnvelope> claimDue(List<CommandChannelEnum> channels, int limit) {
        LocalDateTime now = LocalDateTime.now();
        // 调度线程无登录态，租户拦截器会追加错误的 tenant_id 条件导致非零租户命令
        // 永久不可领取；命令消费的租户语义由信封 tenant_id 承载并在消费前校验，
        // 领取本身必须跨租户扫描（I5 收口：移除「仅可靠消费租户 0」的受理边界）。
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return claimDueSuspended(channels, limit, now);
        }
    }

    private List<CommandEnvelope> claimDueSuspended(List<CommandChannelEnum> channels, int limit,
                                                    LocalDateTime now) {
        // 租户公平领取（P62 资源保障）：全局 FIFO 会被单租户突发占满，活跃租户并存时
        // 按租户切片轮流领取，保证每个活跃租户每轮都有推进机会（有界服务）；
        // 仅单租户有到期命令时取整批额度（行为与旧实现一致）。
        List<Long> activeTenants = commandService.listMaps(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmCommand>()
                                .select("DISTINCT tenant_id")
                                .eq("status", CommandStatusEnum.PENDING.getCode())
                                .in("channel", channels.stream().map(Enum::name).toList())
                                .and(wrapper -> wrapper.isNull("next_retry_at")
                                        .or().le("next_retry_at", now)))
                .stream()
                .map(row -> {
                    // H2/PG 列标签大小写与驼峰映射差异：大小写不敏感取租户列
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        if ("tenant_id".equalsIgnoreCase(entry.getKey())) {
                            return ((Number) entry.getValue()).longValue();
                        }
                    }
                    throw new IllegalStateException("租户公平领取查询缺少 tenant_id 列");
                })
                .toList();
        int slice = activeTenants.size() <= 1 ? limit
                : Math.max(1, limit / activeTenants.size());
        int batchClaimed = 0;
        int batchClaimLimit = activeBatchPollClaimLimit();
        List<CommandEnvelope> claimed = new ArrayList<>();
        for (Long tenantId : activeTenants) {
            List<BpmCommand> candidates = commandService.lambdaQuery()
                    .eq(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                    .in(BpmCommand::getChannel, channels.stream().map(Enum::name).toList())
                    .eq(BpmCommand::getTenantId, tenantId)
                    .and(wrapper -> wrapper.isNull(BpmCommand::getNextRetryAt)
                            .or().le(BpmCommand::getNextRetryAt, now))
                    .orderByAsc(BpmCommand::getCreateTime)
                    .last("LIMIT " + slice)
                    .list();
            for (BpmCommand candidate : candidates) {
                // 批量命令每轮限量领取（共享预算内推进，批量不能占完全部执行机会）
                if (CommandTypeEnum.BATCH_INVOKE.getCode().equals(candidate.getCommandType())) {
                    if (batchClaimed >= batchClaimLimit) {
                        continue;
                    }
                    batchClaimed++;
                }
                // 一次性租约令牌：写回（complete/reject/fail）须匹配本令牌，
                // stale 回收后旧持有者的迟到写回因令牌不匹配被拒。
                String claimToken = java.util.UUID.randomUUID().toString();
                LambdaUpdateWrapper<BpmCommand> claim = new LambdaUpdateWrapper<BpmCommand>()
                        .eq(BpmCommand::getId, candidate.getId())
                        .eq(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                        .set(BpmCommand::getStatus, CommandStatusEnum.PROCESSING.getCode())
                        .set(BpmCommand::getClaimedAt, now)
                        .set(BpmCommand::getClaimToken, claimToken);
                if (commandService.update(claim)) {
                    candidate.setStatus(CommandStatusEnum.PROCESSING.getCode());
                    candidate.setClaimedAt(now);
                    candidate.setClaimToken(claimToken);
                    claimed.add(toEnvelope(candidate));
                }
            }
        }
        return claimed;
    }

    /** 每轮批量领取上限（生效策略；未启用策略时不设限=旧行为，默认关闭零变化）。 */
    private int activeBatchPollClaimLimit() {
        com.sw.ck.bpm.process.entity.BpmResourcePolicy policy = admissionService.findActivePolicy();
        if (policy == null) {
            return Integer.MAX_VALUE;
        }
        return policy.getBatchPollClaimLimit() != null
                ? Math.max(1, policy.getBatchPollClaimLimit()) : 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void complete(Long commandId, String claimToken, String resultJson) {
        // PROCESSING + 当前租约令牌双守卫：stale 回收后旧领取者迟到的完成
        // 因令牌不匹配被拒，不覆盖新消费者（仍在 PROCESSING）的结果。
        boolean updated = commandService.lambdaUpdate()
                .eq(BpmCommand::getId, commandId)
                .eq(BpmCommand::getStatus, CommandStatusEnum.PROCESSING.getCode())
                .eq(BpmCommand::getClaimToken, claimToken == null ? "" : claimToken)
                .set(BpmCommand::getStatus, CommandStatusEnum.COMPLETED.getCode())
                .set(BpmCommand::getResult, resultJson)
                .set(BpmCommand::getFailureReason, null)
                .set(BpmCommand::getFinishedAt, LocalDateTime.now())
                .update();
        if (!updated) {
            log.warn("命令完成被跳过: commandId={} 已离开当前领取权（被回收/终结或租约令牌不匹配）", commandId);
            return;
        }
        releaseOnTerminal(commandId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void reject(Long commandId, String claimToken, String reason) {
        boolean updated = commandService.lambdaUpdate()
                .eq(BpmCommand::getId, commandId)
                .eq(BpmCommand::getStatus, CommandStatusEnum.PROCESSING.getCode())
                .eq(BpmCommand::getClaimToken, claimToken == null ? "" : claimToken)
                .set(BpmCommand::getStatus, CommandStatusEnum.FAILED.getCode())
                .set(BpmCommand::getResult, "{\"status\":\"REJECTED\"}")
                .set(BpmCommand::getFailureReason, truncate(reason))
                .set(BpmCommand::getFinishedAt, LocalDateTime.now())
                .set(BpmCommand::getResourceReleasedAt, LocalDateTime.now())
                .update();
        if (!updated) {
            log.warn("命令消费前拒绝被跳过: commandId={} 已离开当前领取权（租约令牌不匹配）", commandId);
            return;
        }
        releaseOnTerminal(commandId);
        log.warn("命令消费前安全门禁拒绝: commandId={}, reason={}", commandId, reason);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean failAndScheduleRetry(Long commandId, String claimToken, String reason,
                                        int maxRetries, long backoffMillis) {
        BpmCommand command = readCommandForFailure(commandId);
        if (command == null) {
            return false;
        }
        // 三重守卫：终态不可复活；非当前租约令牌（stale 回收后旧持有者）不得写回——
        // 既不打回 PENDING 扰乱当前持有者，也不改判其终态。
        // 读取守卫与实际写入之间存在交接窗口：最终 UPDATE 仍须携带本令牌
        // （与 complete/reject 同一领取权条件），读取校验通过不豁免写入校验。
        String status = command.getStatus();
        if (CommandStatusEnum.COMPLETED.getCode().equals(status)
                || CommandStatusEnum.FAILED.getCode().equals(status)
                || CommandStatusEnum.EXPIRED.getCode().equals(status)) {
            log.warn("命令失败处理被跳过: commandId={} 已是终态 {}", commandId, status);
            return false;
        }
        String currentToken = command.getClaimToken();
        if (status.equals(CommandStatusEnum.PROCESSING.getCode())
                && !java.util.Objects.equals(currentToken, claimToken)) {
            log.warn("命令失败处理被跳过: commandId={} 租约令牌不匹配（当前持有者仍在处理，"
                    + "旧持有者迟到写回被拒）", commandId);
            return false;
        }
        int retryCount = command.getRetryCount() == null ? 0 : command.getRetryCount();
        if (retryCount + 1 >= maxRetries) {
            // 调度线程可能无登录态（如"无命令处理器"拒绝路径先于身份还原）：
            // 与读取同口径挂起租户过滤，命令行 tenant_id 自承载、主键+状态+令牌定位
            try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                         com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
                boolean failed = commandService.lambdaUpdate()
                        .eq(BpmCommand::getId, commandId)
                        .eq(BpmCommand::getStatus, status)
                        .eq(BpmCommand::getClaimToken, claimToken == null ? "" : claimToken)
                        .set(BpmCommand::getStatus, CommandStatusEnum.FAILED.getCode())
                        .set(BpmCommand::getFailureReason, truncate(reason))
                        .set(BpmCommand::getFinishedAt, LocalDateTime.now())
                        .set(BpmCommand::getResourceReleasedAt, LocalDateTime.now())
                        .update();
                if (!failed) {
                    log.warn("命令终态失败改判被跳过: commandId={} 状态已变化", commandId);
                    return false;
                }
                releaseOnTerminal(commandId);
                log.warn("命令终态失败: commandId={}, retries={}, reason={}", commandId, retryCount + 1, reason);
                return false;
            }
        }
        long backoff = backoffMillis * (1L << Math.min(retryCount, 10));
        boolean retried;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            retried = commandService.lambdaUpdate()
                    .eq(BpmCommand::getId, commandId)
                    .eq(BpmCommand::getStatus, status)
                    .eq(BpmCommand::getClaimToken, claimToken == null ? "" : claimToken)
                    .set(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                    .set(BpmCommand::getRetryCount, retryCount + 1)
                    .set(BpmCommand::getNextRetryAt, LocalDateTime.now().plusNanos(backoff * 1_000_000))
                    .set(BpmCommand::getFailureReason, truncate(reason))
                    .update();
        }
        if (!retried) {
            log.warn("命令重试改派被跳过: commandId={} 状态已变化", commandId);
            return false;
        }
        log.info("命令将重试: commandId={}, retry={}，退避 {}ms，reason={}",
                commandId, retryCount + 1, backoff, reason);
        return true;
    }

    /** 失败处理前的命令读取；测试以此注入"读取后、写入前"的交接窗口快照。 */
    protected BpmCommand readCommandForFailure(Long commandId) {
        // 调度线程无登录态（含"无命令处理器"拒绝路径先于身份还原）：与 claimDue/reclaimStale
        // 同口径挂起租户过滤——命令行 tenant_id 自承载租户语义，主键定位不涉及租户裁剪
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return commandService.getById(commandId);
        }
    }

    @Override
    public int reclaimStale(LocalDateTime staleBefore) {
        // 调度线程无登录态：与 claimDue 同口径挂起租户过滤（孤儿命令为全局对象，
        // 按状态与领取时间回收，不涉及租户语义）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            boolean updated = commandService.lambdaUpdate()
                    .eq(BpmCommand::getStatus, CommandStatusEnum.PROCESSING.getCode())
                    .lt(BpmCommand::getClaimedAt, staleBefore)
                    .set(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                    .set(BpmCommand::getClaimToken, null)
                    .update();
            return updated ? 1 : 0;
        }
    }

    @Override
    public Optional<CommandEnvelope> findById(Long commandId) {
        return Optional.ofNullable(commandService.getById(commandId)).map(this::toEnvelope);
    }

    @Override
    public boolean requeueForContinuation(Long commandId, String claimToken) {
        // 分片续跑：不计失败重试、不清占用、立即重领；仅当前领取权可触发
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            boolean updated = commandService.lambdaUpdate()
                    .eq(BpmCommand::getId, commandId)
                    .eq(BpmCommand::getStatus, CommandStatusEnum.PROCESSING.getCode())
                    .eq(BpmCommand::getClaimToken, claimToken == null ? "" : claimToken)
                    .set(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                    .set(BpmCommand::getNextRetryAt, LocalDateTime.now())
                    .set(BpmCommand::getClaimedAt, null)
                    .set(BpmCommand::getClaimToken, null)
                    .update();
            if (!updated) {
                log.warn("命令分片续跑被跳过: commandId={} 已离开当前领取权", commandId);
            }
            return updated;
        }
    }

    private void applyTieredSemantics(BpmCommand command, CommandEnvelope envelope) {
        int seconds = envelope.getDeadlineSeconds() == 0
                ? DEADLINE_SECONDS_DEFAULT : envelope.getDeadlineSeconds();
        if (seconds < DEADLINE_SECONDS_MIN || seconds > DEADLINE_SECONDS_MAX) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "准入截止秒数必须在 " + DEADLINE_SECONDS_MIN + "—" + DEADLINE_SECONDS_MAX + " 之间: " + seconds);
        }
        command.setLogicalCommandId(blankToNull(envelope.getLogicalCommandId()));
        command.setPayloadFingerprint(blankToNull(envelope.getPayloadFingerprint()));
        command.setTier(blankToNull(envelope.getTier()));
        command.setCompletionPoint(blankToNull(envelope.getCompletionPoint()));
        command.setDeadlineAt(LocalDateTime.now().plusSeconds(seconds));
        envelope.setDeadlineAt(command.getDeadlineAt());
    }

    // ==================== P62 资源保障：占用冻结/释放/再占用 ====================

    /**
     * 受理时冻结资源会计字段（准入成功才有值；策略未启用时保持 NULL，
     * 旧对象与未启用受理不参与资源会计）。
     */
    private void applyResourceAccounting(BpmCommand command, CommandEnvelope envelope) {
        command.setResourceClass(blankToNull(envelope.getResourceClass()));
        command.setResourceUnits(envelope.getResourceUnits());
        command.setResourceSegment(blankToNull(envelope.getResourceSegment()));
        command.setPolicyVersion(envelope.getPolicyVersion());
    }

    /** 终态释放判定：终态写入成功后调用（同一事务），按冻结字段回收占用。 */
    private void releaseOnTerminal(Long commandId) {
        BpmCommand command;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            command = commandService.getById(commandId);
        }
        if (command != null) {
            releaseService.onTerminal(command);
        }
    }

    /**
     * 准入截止扫描：仅 PENDING（待执行）且<strong>效果未发生</strong>（无效果权威行）的到期命令
     * 收敛为 EXPIRED。执行中（PROCESSING）不得据此判过期，由 {@link #markOverdue} 仅记超期。
     *
     * @return 本次过期条数
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int expireDue(LocalDateTime now) {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            List<BpmCommand> candidates = commandService.lambdaQuery()
                    .eq(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                    .isNotNull(BpmCommand::getDeadlineAt)
                    .le(BpmCommand::getDeadlineAt, now)
                    .orderByAsc(BpmCommand::getDeadlineAt)
                    .last("LIMIT 200")
                    .list();
            int expired = 0;
            for (BpmCommand candidate : candidates) {
                if (effectMapper.selectById(candidate.getId()) != null) {
                    continue; // 效果已发生：不得判过期（等待权威结果收敛）
                }
                boolean updated = commandService.lambdaUpdate()
                        .eq(BpmCommand::getId, candidate.getId())
                        .eq(BpmCommand::getStatus, CommandStatusEnum.PENDING.getCode())
                        .le(BpmCommand::getDeadlineAt, now)
                        .set(BpmCommand::getStatus, CommandStatusEnum.EXPIRED.getCode())
                        .set(BpmCommand::getFailureReason, "准入截止到期且未执行（效果未发生）")
                        .set(BpmCommand::getFinishedAt, now)
                        .set(BpmCommand::getResourceReleasedAt, now)
                        .update();
                if (updated) {
                    // 过期=执行权终止：按冻结字段释放占用（批量按剩余非终态项）
                    releaseService.onTerminal(candidate);
                    expired++;
                }
            }
            if (expired > 0) {
                log.warn("准入截止：{} 条待执行命令因效果未发生而过期（EXPIRED）", expired);
            }
            return expired;
        }
    }

    /**
     * 执行中超期标记：PROCESSING 且已过截止且未标记的命令写 {@code overdue_at}（证据字段），
     * 不改状态、不判失败/过期——等待权威结果确定。
     *
     * @return 本次标记条数
     */
    public int markOverdue(LocalDateTime now) {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            List<BpmCommand> candidates = commandService.lambdaQuery()
                    .eq(BpmCommand::getStatus, CommandStatusEnum.PROCESSING.getCode())
                    .isNotNull(BpmCommand::getDeadlineAt)
                    .le(BpmCommand::getDeadlineAt, now)
                    .isNull(BpmCommand::getOverdueAt)
                    .last("LIMIT 200")
                    .list();
            int marked = 0;
            for (BpmCommand candidate : candidates) {
                boolean updated = commandService.lambdaUpdate()
                        .eq(BpmCommand::getId, candidate.getId())
                        .eq(BpmCommand::getStatus, CommandStatusEnum.PROCESSING.getCode())
                        .isNull(BpmCommand::getOverdueAt)
                        .set(BpmCommand::getOverdueAt, now)
                        .update();
                if (updated) {
                    marked++;
                }
            }
            return marked;
        }
    }

    /** 统一逻辑身份查询（同身份重放/异载荷冲突判定；租户显式传入）。 */
    public Optional<CommandEnvelope> findByLogicalId(Long tenantId, String logicalCommandId) {
        if (logicalCommandId == null || logicalCommandId.isBlank()) {
            return Optional.empty();
        }
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            BpmCommand command = commandService.lambdaQuery()
                    .eq(BpmCommand::getTenantId, tenantId)
                    .eq(BpmCommand::getLogicalCommandId, logicalCommandId)
                    .last("LIMIT 1")
                    .one();
            return Optional.ofNullable(command).map(this::toEnvelope);
        }
    }

    /**
     * 待对账清单：PROCESSING 且已有权威效果行（含提交后完成记录未写窗口）。
     * 供恢复任务据权威结果确定收敛，不重做业务。
     */
    public List<CommandEnvelope> listProcessingWithEffect(int limit) {
        // 对账线程无登录态：效果/命令两表查询全程挂起租户过滤（行内 tenant_id 自承载，
        // 与 expireDue/markOverdue 同口径；主键定位不涉及租户裁剪）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            List<BpmCommandEffect> effects = effectMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmCommandEffect>()
                            .orderByAsc("create_time")
                            .last("LIMIT " + Math.max(1, limit)));
            List<CommandEnvelope> result = new ArrayList<>();
            for (BpmCommandEffect effect : effects) {
                BpmCommand command = commandService.getById(effect.getCommandId());
                    if (command != null && CommandStatusEnum.PROCESSING.getCode().equals(command.getStatus())) {
                        result.add(toEnvelope(command));
                    }
                }
            return result;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private CommandEnvelope toEnvelope(BpmCommand command) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(command.getId());
        envelope.setCommandType(CommandTypeEnum.of(command.getCommandType()));
        envelope.setChannel(CommandChannelEnum.valueOf(command.getChannel()));
        envelope.setCommandKey(command.getCommandKey());
        envelope.setTenantId(command.getTenantId());
        envelope.setInitiatorId(command.getInitiatorId());
        envelope.setPayload(command.getPayload());
        envelope.setRetryCount(command.getRetryCount() == null ? 0 : command.getRetryCount());
        envelope.setStatus(command.getStatus());
        envelope.setResult(command.getResult());
        envelope.setFailureReason(command.getFailureReason());
        envelope.setClaimToken(command.getClaimToken());
        envelope.setLogicalCommandId(command.getLogicalCommandId());
        envelope.setPayloadFingerprint(command.getPayloadFingerprint());
        envelope.setTier(command.getTier());
        envelope.setCompletionPoint(command.getCompletionPoint());
        envelope.setDeadlineAt(command.getDeadlineAt());
        envelope.setOverdueAt(command.getOverdueAt());
        envelope.setResourceClass(command.getResourceClass());
        envelope.setResourceUnits(command.getResourceUnits());
        envelope.setResourceSegment(command.getResourceSegment());
        envelope.setPolicyVersion(command.getPolicyVersion());
        envelope.setResourceReleasedAt(command.getResourceReleasedAt());
        return envelope;
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 1000 ? reason : reason.substring(0, 997) + "...";
    }
}
