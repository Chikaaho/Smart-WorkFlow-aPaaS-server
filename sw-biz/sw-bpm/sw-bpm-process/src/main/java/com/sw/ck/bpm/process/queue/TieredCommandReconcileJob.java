package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.BpmCommandEffect;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分级命令对账恢复任务（P62 分级执行与统一命令）。
 * <p>
 * 三类收敛，均为"据事实收敛"而非重做业务：
 * <ol>
 *   <li>准入截止到期且效果未发生的待执行命令 → EXPIRED（安全过期）；</li>
 *   <li>执行中超过截止 → 仅记 {@code overdue_at}，等待权威结果；</li>
 *   <li>PROCESSING 且已有权威效果行（业务已提交、完成记录未写/被拒窗口）→
 *       按效果行的权威结果补写完成（令牌守卫沿用 complete 双条件）。</li>
 * </ol>
 * 调度线程无登录态：跨租户扫描由队列方法内部挂起租户过滤，逐条收敛前按命令租户/发起人
 * 还原身份（与 {@link CommandDispatcher} 同口径），不产生"全局超级用户"路径。
 * </p>
 */
@Component
public class TieredCommandReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(TieredCommandReconcileJob.class);

    private static final int RECONCILE_LIMIT = 200;

    private final PersistentBpmCommandQueue queue;
    private final CommandEffectRecorder effectRecorder;

    public TieredCommandReconcileJob(PersistentBpmCommandQueue queue,
                                     CommandEffectRecorder effectRecorder) {
        this.queue = queue;
        this.effectRecorder = effectRecorder;
    }

    @Scheduled(fixedDelayString = "${sw.bpm.command.reconcile-interval-millis:60000}",
            initialDelayString = "${sw.bpm.command.reconcile-initial-delay-millis:30000}")
    public void sweep() {
        try {
            ReconcileResult result = reconcileOnce(LocalDateTime.now());
            if (result.expired() > 0 || result.overdue() > 0 || result.completedFromEffect() > 0) {
                log.info("分级命令对账: expired={}, overdue={}, completedFromEffect={}",
                        result.expired(), result.overdue(), result.completedFromEffect());
            }
        } catch (Exception e) {
            log.error("分级命令对账异常", e);
        }
    }

    /** 单轮对账（参数化当前时间，便于确定性验证）。 */
    public ReconcileResult reconcileOnce(LocalDateTime now) {
        int expired = queue.expireDue(now);
        int overdue = queue.markOverdue(now);
        int completed = 0;
        List<CommandEnvelope> pending = queue.listProcessingWithEffect(RECONCILE_LIMIT);
        for (CommandEnvelope command : pending) {
            BpmCommandEffect effect;
            try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                         com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
                effect = effectRecorder.findEffect(command.getCommandId());
            }
            if (effect == null) {
                continue;
            }
            LoginUser previous = LoginUserHolder.get();
            LoginUser owner = new LoginUser();
            owner.setUserId(command.getInitiatorId());
            owner.setTenantId(command.getTenantId());
            try {
                LoginUserHolder.set(owner);
                queue.complete(command.getCommandId(), command.getClaimToken(), effect.getResultJson());
                completed++;
                log.info("命令据权威效果收敛完成: commandId={}, bizRef={}",
                        command.getCommandId(), effect.getBizRef());
            } finally {
                if (previous == null) {
                    LoginUserHolder.clear();
                } else {
                    LoginUserHolder.set(previous);
                }
            }
        }
        return new ReconcileResult(expired, overdue, completed);
    }

    /** 单轮对账结果。 */
    public record ReconcileResult(int expired, int overdue, int completedFromEffect) {
    }
}
