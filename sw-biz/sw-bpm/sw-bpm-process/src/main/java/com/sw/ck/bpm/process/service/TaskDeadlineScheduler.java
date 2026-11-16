package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.api.common.BatchItemStatusEnum;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTaskDeadline;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.tenant.TenantWorkAdmission;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
/**
 * 人工节点办理时限调度（I3 §4.8）。
 * <p>
 * 到期提醒 / 升级催办 / 受控自动动作的唯一执行入口：
 * 使用 DB 认领（run_state PENDING → DONE 的原子 UPDATE 型推进）保证
 * 服务重启、调度重复与多实例竞争只触发一次合法效果；
 * 通知失败不回滚已完成的审批或自动动作。
 * </p>
 */
@Component
@EnableScheduling
@Log4j2
public class TaskDeadlineScheduler {

    @Autowired
    private BpmTaskDeadlineMapper deadlineMapper;
    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private BpmInstanceService bpmInstanceService;
    /** 防循环依赖的懒访问器。 */
    @Autowired(required = false)
    private ObjectProvider<TaskActionService> taskActionServiceProvider;
    @Autowired(required = false)
    private ObjectProvider<UserQueryFacade> userQueryFacade;
    @Autowired
    private DomainEventPublisher domainEventPublisher;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TenantWorkAdmission tenantWorkAdmission;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private volatile TransactionTemplate notifyTx;

    private TransactionTemplate notifyTx() {
        if (notifyTx == null) {
            notifyTx = new TransactionTemplate(transactionManager);
        }
        return notifyTx;
    }

    /** 每 60 秒扫描一批到期时限；单批次量受控，错峰触发。 */
    @Scheduled(fixedDelay = 60000)
    public void scan() {
        try {
            // 调度线程无登录态：与 PersistentBpmCommandQueue.claimDue 同口径挂起租户过滤
            List<BpmTaskDeadline> due;
            try (Suspended ignored =
                         TenantLineSuspension.suspended()) {
                due = deadlineMapper.selectList(
                        Wrappers.<BpmTaskDeadline>lambdaQuery()
                                .eq(BpmTaskDeadline::getRunState, BatchItemStatusEnum.PENDING.getCode())
                                .lt(BpmTaskDeadline::getDueAt, LocalDateTime.now())
                                .last("LIMIT 50"));
            }
            for (BpmTaskDeadline record : due) {
                try (Suspended suspended =
                             TenantLineSuspension.suspended()) {
                    processSafely(record);
                }
            }
        } catch (Exception e) {
            log.warn("时限扫描失败（下轮重试）: {}", e.getMessage());
        }
    }

    private void processSafely(BpmTaskDeadline record) {
        try {
            // 任务已消失 → 时限随机失效，不再触发（幂等）。
            // 仅 PENDING→DONE 原子推进：不覆盖竞争胜者已写的 AUTO_APPROVE 等结果标注。
            // empty = 该任务不存在（原 null 返回路径）：与“任务已消失”同判
            Optional<BpmTaskDTO> taskLookup = bpmTaskFacade.getTask(record.getTaskId());
            if (taskLookup.isEmpty()) {
                markDoneWithResult(record, "TASK_GONE");
                return;
            }
            BpmTaskDTO task = taskLookup.get();
            if ("APPROVE".equalsIgnoreCase(record.getAutoAction())
                    || "DISAPPROVE".equalsIgnoreCase(record.getAutoAction())
                    || "TRANSFER".equalsIgnoreCase(record.getAutoAction())) {
                runAutoAction(record, task);
                return;
            }
            // 未配置受控自动动作：仅提醒/升级（一次性，幂等标记）。
            // 先原子认领（PENDING→DONE）再通知：多实例竞争只有一实例发通知，双通知=0。
            boolean escalated = record.getEscalationFired() != null
                    && record.getEscalationFired() == 1;
            if (!escalated) {
                if (markClaimed(record) <= 0) {
                    log.info("时限已被其他实例认领，本实例跳过: deadlineId={}, taskId={}",
                            record.getId(), record.getTaskId());
                    return;
                }
                // 通知事件由 AFTER_COMMIT 监听器消费，必须在事务内发布，否则事件被静默丢弃；
                // 认领已先行原子完成，事务内发布仍保证恰一次通知。
                notifyTx().executeWithoutResult(st -> {
                    notifyDeadline(record, task, "升级催办");
                    markResult(record.getId(), "ESCALATED");
                });
            }
        } catch (Exception e) {
            log.warn("时限处理失败: taskId={}, error={}", record.getTaskId(), e.getMessage());
            patch(record, BatchItemStatusEnum.PENDING.getCode(), null);
        }
    }

    private void runAutoAction(BpmTaskDeadline record, BpmTaskDTO task) {
        // 候选任务（无人认领）不做自动动作——受控自动动作只作用于已有责任人
        if (task.getAssignee() == null || task.getAssignee().isBlank()) {
            notifyTx().executeWithoutResult(st -> {
                notifyDeadline(record, task, "升级催办（无自动动作）");
                patch(record, "DONE", "ESCALATED");
            });
            return;
        }
        // 认领：原子 UPDATE 型推进（PENDING → DONE 一次性占位）
        int claimed = markClaimed(record);
        if (claimed <= 0) {
            // 认领竞争：另一实例/重复调度已抢先推进——留证同一 deadline 的双实例扫描事实
            log.info("时限已被其他实例认领，本实例跳过: deadlineId={}, taskId={}",
                    record.getId(), record.getTaskId());
            return; // 已被另一实例/重复调度处理
        }
        BpmInstance instance = bpmInstanceService
                .findByProcessInstanceId(record.getProcessInstanceId()).orElse(null);
        if (instance == null
                || !InstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            patch(record, BpmChildBatch.STATUS_CANCELLED, "INSTANCE_CLOSED");
            return;
        }
        LoginUser synthetic = new LoginUser();
        // 受控自动动作的审计主体为任务原责任人（自动动作以其身份结算）
        try {
            synthetic.setUserId(Long.valueOf(task.getAssignee()));
        } catch (Exception e) {
            synthetic.setUserId(0L);
        }
        synthetic.setTenantId(instance.getTenantId() == null ? 0L
                : instance.getTenantId());
        LoginUserHolder.set(synthetic);
        try {
            ApprovalActionRequest request = new ApprovalActionRequest();
            request.setAction(ApprovalAction.APPROVE);
            if ("DISAPPROVE".equalsIgnoreCase(record.getAutoAction())) {
                request.setAction(ApprovalAction.DISAPPROVE);
            } else if ("TRANSFER".equalsIgnoreCase(record.getAutoAction())) {
                request.setAction(ApprovalAction.TRANSFER);
                Map<String, Object> config = parseConfig(record.getAutoActionConfig());
                Object target = config.get("transferToUserId");
                if (target instanceof Number number) {
                    request.setTargetUserId(number.longValue());
                } else if (target != null && !String.valueOf(target).isBlank()) {
                    request.setTargetUserId(Long.valueOf(String.valueOf(target)));
                }
                request.setReason("办理超时自动转办");
            }
            if (request.getAction() == ApprovalAction.TRANSFER
                    && request.getTargetUserId() == null) {
                patch(record, "DONE", "AUTO_SKIPPED_NO_TARGET");
                return;
            }
            TaskActionService taskActionService = taskActionServiceProvider == null
                    ? null : taskActionServiceProvider.getIfAvailable();
            if (taskActionService != null) {
                taskActionService.execute(record.getTaskId(), request);
            }
            patch(record, "DONE", "AUTO_" + record.getAutoAction().toUpperCase());
            log.info("时限自动动作完成: taskId={}, action={}", record.getTaskId(),
                    request.getAction());
        } finally {
            LoginUserHolder.clear();
        }
    }

    private void notifyDeadline(BpmTaskDeadline record, BpmTaskDTO task, String phase) {
        try {
            Long recipient = task.getAssignee() == null || !task.getAssignee().matches("\\d+")
                    ? null : Long.valueOf(task.getAssignee());
            if (recipient == null) {
                return;
            }
            BpmInstance instance = bpmInstanceService
                    .findByProcessInstanceId(record.getProcessInstanceId()).orElse(null);
            if (instance == null) {
                return;
            }
            domainEventPublisher.publish(new BpmNotifyEvent(
                    BpmNotifyTrigger.TASK_DEADLINE_ALERT, recipient,
                    instance.getTenantId(), 0L, record.getTaskId()));
        } catch (Exception e) {
            log.warn("时限提醒通知失败（不回滚时限状态）: taskId={}, error={}",
                    record.getTaskId(), e.getMessage());
        }
    }

    /** 原子认领：仅 PENDING 行推进为 DONE；0 行=已处理。 */
    private int markClaimed(BpmTaskDeadline record) {
        return tenantWorkAdmission.claim(record.getTenantId(), () -> markClaimedPersisted(record));
    }

    private int markClaimedPersisted(BpmTaskDeadline record) {
        BpmTaskDeadline patch = new BpmTaskDeadline();
        patch.setRunState("DONE");
        patch.setHandledAt(LocalDateTime.now());
        return deadlineMapper.update(patch,
                Wrappers.<BpmTaskDeadline>lambdaUpdate()
                        .eq(BpmTaskDeadline::getId, record.getId())
                        .eq(BpmTaskDeadline::getTenantId, record.getTenantId())
                        .eq(BpmTaskDeadline::getRunState, BatchItemStatusEnum.PENDING.getCode()));
    }

    /** 任务已消失分支的原子推进：仅 PENDING 行写 DONE/TASK_GONE，不覆盖胜者结果。 */
    private void markDoneWithResult(BpmTaskDeadline record, String result) {
        tenantWorkAdmission.claim(record.getTenantId(), () -> {
            BpmTaskDeadline patch = new BpmTaskDeadline();
            patch.setRunState("DONE");
            patch.setHandledAt(LocalDateTime.now());
            patch.setResultStatus(result);
            return deadlineMapper.update(patch,
                    Wrappers.<BpmTaskDeadline>lambdaUpdate()
                            .eq(BpmTaskDeadline::getId, record.getId())
                            .eq(BpmTaskDeadline::getTenantId, record.getTenantId())
                            .eq(BpmTaskDeadline::getRunState, BatchItemStatusEnum.PENDING.getCode()));
        });
    }

    /** 认领后补充结果标注（仅写 result_status，不改 run_state）。 */
    private void markResult(Long id, String result) {
        BpmTaskDeadline patch = new BpmTaskDeadline();
        patch.setResultStatus(result);
        deadlineMapper.update(patch,
                Wrappers.<BpmTaskDeadline>lambdaUpdate()
                        .eq(BpmTaskDeadline::getId, id));
    }

    private void patch(BpmTaskDeadline record, String runState, String resultStatus) {
        BpmTaskDeadline patch = new BpmTaskDeadline();
        patch.setId(record.getId());
        patch.setRunState(runState);
        if (resultStatus != null) {
            patch.setResultStatus(resultStatus);
        }
        if (record.getAutoAction() == null && "DONE".equals(runState)) {
            patch.setHandledAt(LocalDateTime.now());
        }
        deadlineMapper.updateById(patch);
    }

    private Map<String, Object> parseConfig(String config) {
        try {
            return config == null || config.isBlank() ? Map.of()
                    : objectMapper.readValue(config, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

}
