package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.BatchStatusEnum;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.spi.UserDetailsProvider;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/**
 * 命令调度器：持久化队列的默认消费者。
 * <p>
 * 两条独立调度车道：NORMAL（普通 OA 批量轮询）与 P0（更高频、独立容量），
 * P0 不与普通命令共享同一批领取，普通积压不阻塞 P0（D2）。
 * 调度经自有 {@link ScheduledExecutorService} 驱动（不依赖 @EnableScheduling，
 * 调度实际生效以运行日志与行为验证为准）。
 * </p>
 * <p>
 * 每条命令消费前从受理记录还原 LoginUserHolder（租户/发起人），线程复用后在
 * finally 清理，杜绝跨用户/跨租户串用（D5）。失败按指数退避有界重试，超过
 * 上限进入 FAILED 终态；崩溃残留的 PROCESSING 由 stale 恢复路径重新入队。
 * </p>
 */
@Component
@Log4j2
public class CommandDispatcher {

    @Autowired
    private BpmCommandQueue commandQueue;
    /** 命令处理器按条件装配（如 BatchInvokeCommandHandler 带 @ConditionalOnProperty），
     * 零实现时 dispatch 不可用但注册表仍须可构造；构造器注入时代空列表合法，
     * required=false + 空兜底恢复该语义（1.1.3 CI：d53c7fb6 字段注入改造后零 Bean 环境启动失败）。 */
    @Autowired(required = false)
    private List<BpmCommandHandler> handlerList = new ArrayList<>();
    private Map<CommandTypeEnum, BpmCommandHandler> handlers;
    @Autowired(required = false)
    private UserDetailsProvider userDetailsProvider;

    private static final String P0_PERMISSION = "workflow:p0:dispatch";

    @Value("${sw.bpm.command.poll-interval-millis:500}")
    private long pollIntervalMillis;

    @Value("${sw.bpm.command.p0-poll-interval-millis:100}")
    private long p0PollIntervalMillis;

    @Value("${sw.bpm.command.batch-size:20}")
    private int batchSize;

    @Value("${sw.bpm.command.p0-batch-size:5}")
    private int p0BatchSize;

    @Value("${sw.bpm.command.max-retries:5}")
    private int maxRetries;

    @Value("${sw.bpm.command.backoff-millis:1000}")
    private long backoffMillis;

    @Value("${sw.bpm.command.stale-seconds:60}")
    private long staleSeconds;

    private ScheduledExecutorService scheduler;

    @Value("${sw.bpm.command.dispatch-workers:3}")
    private int dispatchWorkers;

    private ExecutorService dispatchPool;

    @PostConstruct
    public void start() {
        HashMap<CommandTypeEnum, BpmCommandHandler> registry = new HashMap<>();
        for (BpmCommandHandler handler : handlerList) {
            for (CommandTypeEnum type : handler.types()) {
                BpmCommandHandler previous = registry.put(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("命令类型重复注册: " + type);
                }
            }
        }
        this.handlers = Collections.unmodifiableMap(registry);
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "bpm-command-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        // 领取与消费解耦（RA02b2 公平等待）：单线程串行消费时一次轮询 50 条的处理器耗时
        // 串行累加（实测 p50 21ms/p99 321ms），高峰把轮询间隔拉长到数十秒，普通命令领取
        // 等待超合同上界（普通OA≤5s）。执行并发默认 3（sw.bpm.command.dispatch-workers），
        // 领取节奏/批量不变（100ms/批50=合同画像），批量限额与租户切片公平仍在领取侧生效。
        dispatchPool = Executors.newFixedThreadPool(Math.max(1, dispatchWorkers), runnable -> {
            Thread thread = new Thread(runnable, "bpm-command-worker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::pollNormal, pollIntervalMillis, pollIntervalMillis,
                TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::pollP0, p0PollIntervalMillis, p0PollIntervalMillis,
                TimeUnit.MILLISECONDS);
        log.info("命令调度器已启动: normalPoll={}ms, p0Poll={}ms, batch={}, p0Batch={}, maxRetries={}",
                pollIntervalMillis, p0PollIntervalMillis, batchSize, p0BatchSize, maxRetries);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 单命令消费（public 测试接缝，供隔离升级/回退演练直调指定命令，
     * 与 dispatchOne 同一消费语义：身份回查→处理器分发→完成/重试/拒绝）。
     */
    public void dispatchOneForTest(CommandEnvelope envelope) {
        dispatchOne(envelope);
    }

    /** 普通车道轮询（包内可见，供测试直调）。 */
    void pollNormal() {
        try {
            dispatchLane(CommandChannelEnum.NORMAL, batchSize);
        } catch (Exception e) {
            log.error("普通命令轮询异常", e);
        }
    }

    /** P0 车道轮询（包内可见，供测试直调）。 */
    void pollP0() {
        try {
            dispatchLane(CommandChannelEnum.P0, p0BatchSize);
        } catch (Exception e) {
            log.error("P0 命令轮询异常", e);
        }
    }

    private void dispatchLane(CommandChannelEnum channel, int limit) {
        commandQueue.reclaimStale(LocalDateTime.now().minusSeconds(staleSeconds));
        List<CommandEnvelope> claimed = commandQueue.claimDue(List.of(channel), limit);
        if (claimed.isEmpty()) {
            return;
        }
        if (dispatchPool == null || claimed.size() == 1) {
            claimed.forEach(this::dispatchOne);
            return;
        }
        List<Future<?>> futures =
                new ArrayList<>(claimed.size());
        for (CommandEnvelope envelope : claimed) {
            futures.add(dispatchPool.submit(() -> dispatchOne(envelope)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                log.error("命令并行消费异常: {}", String.valueOf(e.getCause()), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void dispatchOne(CommandEnvelope envelope) {
        BpmCommandHandler handler = handlers.get(envelope.getCommandType());
        if (handler == null) {
            commandQueue.failAndScheduleRetry(envelope.getCommandId(), envelope.getClaimToken(),
                    "无命令处理器: " + envelope.getCommandType(), maxRetries, backoffMillis);
            return;
        }
        LoginUser loginUser;
        try {
            if (userDetailsProvider != null && !ownsClaim(envelope)) {
                log.info("命令租约已失效，跳过旧消费者: commandId={}", envelope.getCommandId());
                return;
            }
            loginUser = restoreCurrentIdentity(envelope);
        } catch (RuntimeException exception) {
            rejectBeforeHandle(envelope, "消费身份回查失败: " + exception.getMessage(), exception);
            return;
        }
        if (loginUser == null) {
            rejectBeforeHandle(envelope, "发起用户不存在、已停用或租户不匹配", null);
            return;
        }
        if (envelope.getChannel() == CommandChannelEnum.P0 && !hasP0Permission(loginUser)) {
            rejectBeforeHandle(envelope, "消费时缺少 P0 调用专用权限: " + P0_PERMISSION, null);
            return;
        }
        LoginUserHolder.set(loginUser);
        long begin = System.currentTimeMillis();
        try {
            log.info("命令开始处理: commandId={}, type={}, channel={}, retry={}",
                    envelope.getCommandId(), envelope.getCommandType(), envelope.getChannel(),
                    envelope.getRetryCount());
            String result = handler.handle(envelope);
            commandQueue.complete(envelope.getCommandId(), envelope.getClaimToken(), result);
            log.info("命令处理完成: commandId={}, 耗时 {}ms", envelope.getCommandId(),
                    System.currentTimeMillis() - begin);
        } catch (CommandContinuationSignal continuation) {
            // 分片续跑：处理器已把命令重新入队（不计失败、占用不变），本次不写终态；
            // 剩余分片由后续轮询继续（共享预算内保留其他工作的推进机会）
            log.info("命令分片续跑让出调度权: commandId={}, reason={}",
                    envelope.getCommandId(), continuation.getMessage());
        } catch (Exception e) {
            String reason = e instanceof BaseException base
                    ? base.getMessage() : String.valueOf(e);
            log.warn("命令处理失败: commandId={}, reason={}", envelope.getCommandId(), reason, e);
            boolean retried = commandQueue.failAndScheduleRetry(envelope.getCommandId(),
                    envelope.getClaimToken(), reason, maxRetries, backoffMillis);
            if (!retried) {
                try {
                    LoginUser finalUser = new LoginUser();
                    finalUser.setUserId(envelope.getInitiatorId());
                    finalUser.setTenantId(envelope.getTenantId());
                    LoginUserHolder.set(finalUser);
                    handler.onFinalFailure(envelope, reason);
                } finally {
                    LoginUserHolder.clear();
                }
            }
        } finally {
            LoginUserHolder.clear();
        }
    }

    private LoginUser restoreCurrentIdentity(CommandEnvelope envelope) {
        LoginUser fallback = new LoginUser();
        fallback.setUserId(envelope.getInitiatorId());
        fallback.setTenantId(envelope.getTenantId());
        if (userDetailsProvider == null) {
            // 仅隔离单元测试使用；生产 Spring 构造器始终注入正式 SPI。
            return fallback;
        }

        // 先注入租户以便 UserDetailsProvider 的正式查询走同一租户边界。
        LoginUserHolder.set(fallback);
        LoginUser current = userDetailsProvider.loadClaimedWorkIdentity(envelope.getInitiatorId(), envelope.getTenantId());
        if (current == null || !Objects.equals(current.getUserId(), envelope.getInitiatorId())
                || !Objects.equals(current.getTenantId(), envelope.getTenantId())) {
            return null;
        }
        return current;
    }

    private boolean ownsClaim(CommandEnvelope envelope) {
        if (envelope.getClaimToken() == null || envelope.getClaimToken().isBlank()) return false;
        try (var ignored = TenantLineSuspension.suspended()) {
            return commandQueue.findById(envelope.getCommandId()).filter(current ->
                    BatchStatusEnum.PROCESSING.getCode().equals(current.getStatus())
                            && Objects.equals(current.getClaimToken(), envelope.getClaimToken())
                            && Objects.equals(current.getTenantId(), envelope.getTenantId())
                            && Objects.equals(current.getInitiatorId(), envelope.getInitiatorId())).isPresent();
        }
    }

    private boolean hasP0Permission(LoginUser loginUser) {
        if (userDetailsProvider == null) {
            // 无 SPI 的双参构造器仅供隔离队列测试使用，沿用测试身份语义；
            // Spring 生产构造器始终注入正式 UserDetailsProvider 并执行真实权限校验。
            return true;
        }
        return loginUser.isSuperAdmin()
                || (loginUser.getPermissions() != null && loginUser.getPermissions().contains(P0_PERMISSION));
    }

    private void rejectBeforeHandle(CommandEnvelope envelope, String reason, RuntimeException cause) {
        try {
            commandQueue.reject(envelope.getCommandId(), envelope.getClaimToken(), reason);
            // 消费前拒绝同样是 FAILED 终态：触发处理器补偿（如草稿转 FAILED 可修正重试），
            // 避免 SUBMITTING 永久冻结。以发起人最小身份执行，且不得覆盖真实拒绝原因。
            try {
                LoginUser minimal = new LoginUser();
                minimal.setUserId(envelope.getInitiatorId());
                minimal.setTenantId(envelope.getTenantId());
                LoginUserHolder.set(minimal);
                BpmCommandHandler handler = handlers.get(envelope.getCommandType());
                if (handler != null) {
                    handler.onFinalFailure(envelope, reason);
                }
            } catch (Exception compensationFailure) {
                log.warn("消费前拒绝补偿失败: commandId={}", envelope.getCommandId(), compensationFailure);
            } finally {
                LoginUserHolder.clear();
            }
            if (cause == null) {
                log.warn("命令消费前安全门禁拒绝: commandId={}, reason={}", envelope.getCommandId(), reason);
            } else {
                log.warn("命令消费前身份回查失败，拒绝执行: commandId={}", envelope.getCommandId(), cause);
            }
        } finally {
            LoginUserHolder.clear();
        }
    }
}
