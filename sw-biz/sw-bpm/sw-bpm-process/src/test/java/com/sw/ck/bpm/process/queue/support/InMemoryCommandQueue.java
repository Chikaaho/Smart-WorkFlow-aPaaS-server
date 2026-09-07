package com.sw.ck.bpm.process.queue.support;

import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * G7 替代消息边界实现（进程内内存队列，隔离契约验证用）。
 * <p>
 * 与 {@code PersistentBpmCommandQueue} 实现同一 {@link BpmCommandQueue} 契约：
 * 投递/原子领取/确认/有界重试退避/失败终态/stale 恢复/幂等键冲突（DuplicateKeyException）。
 * 仅用于验证消息边界契约可替换性，不用于生产。
 * </p>
 */
public class InMemoryCommandQueue implements BpmCommandQueue {

    private final Map<Long, BpmCommand> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1000);

    @Override
    public Long enqueue(CommandEnvelope envelope) {
        boolean conflict = store.values().stream()
                .anyMatch(c -> envelope.getTenantId().equals(c.getTenantId())
                        && envelope.getCommandKey().equals(c.getCommandKey()));
        if (conflict) {
            throw new DuplicateKeyException("command_key 冲突: " + envelope.getCommandKey());
        }
        BpmCommand command = new BpmCommand();
        command.setId(idGen.incrementAndGet());
        command.setCommandKey(envelope.getCommandKey());
        command.setCommandType(envelope.getCommandType().getCode());
        command.setChannel(envelope.getChannel().getCode());
        command.setStatus(CommandStatusEnum.PENDING.getCode());
        command.setPayload(envelope.getPayload());
        command.setRetryCount(0);
        command.setInitiatorId(envelope.getInitiatorId());
        command.setTenantId(envelope.getTenantId());
        command.setCreateTime(LocalDateTime.now());
        store.put(command.getId(), command);
        envelope.setCommandId(command.getId());
        return command.getId();
    }

    @Override
    public Optional<CommandEnvelope> findByKey(Long tenantId, String commandKey) {
        return store.values().stream()
                .filter(c -> tenantId.equals(c.getTenantId()) && commandKey.equals(c.getCommandKey()))
                .findFirst()
                .map(this::toEnvelope);
    }

    @Override
    public synchronized List<CommandEnvelope> claimDue(List<CommandChannelEnum> channels, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<BpmCommand> candidates = store.values().stream()
                .filter(c -> CommandStatusEnum.PENDING.getCode().equals(c.getStatus()))
                .filter(c -> channels.contains(CommandChannelEnum.valueOf(c.getChannel())))
                .filter(c -> c.getNextRetryAt() == null || !c.getNextRetryAt().isAfter(now))
                .sorted(Comparator.comparing(BpmCommand::getCreateTime))
                .limit(limit)
                .toList();
        List<CommandEnvelope> claimed = new ArrayList<>();
        for (BpmCommand candidate : candidates) {
            // 原子领取语义：仅 PENDING 可转 PROCESSING；一次性租约令牌与持久实现同契约
            if (CommandStatusEnum.PENDING.getCode().equals(candidate.getStatus())) {
                String claimToken = java.util.UUID.randomUUID().toString();
                candidate.setStatus(CommandStatusEnum.PROCESSING.getCode());
                candidate.setClaimedAt(now);
                candidate.setClaimToken(claimToken);
                claimed.add(toEnvelope(candidate));
            }
        }
        return claimed;
    }

    @Override
    public void complete(Long commandId, String claimToken, String resultJson) {
        BpmCommand command = store.get(commandId);
        if (command != null
                && CommandStatusEnum.PROCESSING.getCode().equals(command.getStatus())
                && java.util.Objects.equals(command.getClaimToken(), claimToken)) {
            command.setStatus(CommandStatusEnum.COMPLETED.getCode());
            command.setResult(resultJson);
            command.setFinishedAt(LocalDateTime.now());
        }
    }

    @Override
    public void reject(Long commandId, String claimToken, String reason) {
        BpmCommand command = store.get(commandId);
        if (command != null && CommandStatusEnum.PROCESSING.getCode().equals(command.getStatus())
                && java.util.Objects.equals(command.getClaimToken(), claimToken)) {
            command.setStatus(CommandStatusEnum.FAILED.getCode());
            command.setResult("{\"status\":\"REJECTED\"}");
            command.setFailureReason(reason);
            command.setFinishedAt(LocalDateTime.now());
        }
    }

    @Override
    public boolean failAndScheduleRetry(Long commandId, String claimToken, String reason,
                                        int maxRetries, long backoffMillis) {
        BpmCommand command = store.get(commandId);
        if (command == null) {
            return false;
        }
        String status = command.getStatus();
        if (CommandStatusEnum.COMPLETED.getCode().equals(status)
                || CommandStatusEnum.FAILED.getCode().equals(status)) {
            return false;
        }
        if (CommandStatusEnum.PROCESSING.getCode().equals(status)
                && !java.util.Objects.equals(command.getClaimToken(), claimToken)) {
            return false;
        }
        int retryCount = command.getRetryCount() == null ? 0 : command.getRetryCount();
        if (retryCount + 1 >= maxRetries) {
            command.setStatus(CommandStatusEnum.FAILED.getCode());
            command.setFailureReason(reason);
            command.setFinishedAt(LocalDateTime.now());
            return false;
        }
        command.setRetryCount(retryCount + 1);
        command.setStatus(CommandStatusEnum.PENDING.getCode());
        command.setNextRetryAt(LocalDateTime.now().plusNanos(backoffMillis * (1L << retryCount) * 1_000_000));
        command.setFailureReason(reason);
        return true;
    }

    @Override
    public int reclaimStale(LocalDateTime staleBefore) {
        int count = 0;
        for (BpmCommand command : store.values()) {
            if (CommandStatusEnum.PROCESSING.getCode().equals(command.getStatus())
                    && command.getClaimedAt() != null && command.getClaimedAt().isBefore(staleBefore)) {
                command.setStatus(CommandStatusEnum.PENDING.getCode());
                command.setClaimToken(null);
                count++;
            }
        }
        return count;
    }

    @Override
    public Optional<CommandEnvelope> findById(Long commandId) {
        return Optional.ofNullable(store.get(commandId)).map(this::toEnvelope);
    }

    /** 测试辅助：模拟退避到期。 */
    public void forceDue(Long commandId) {
        BpmCommand command = store.get(commandId);
        if (command != null) {
            command.setNextRetryAt(null);
        }
    }

    private CommandEnvelope toEnvelope(BpmCommand command) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(command.getId());
        envelope.setCommandType(com.sw.ck.bpm.process.entity.CommandTypeEnum.of(command.getCommandType()));
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
        return envelope;
    }
}
