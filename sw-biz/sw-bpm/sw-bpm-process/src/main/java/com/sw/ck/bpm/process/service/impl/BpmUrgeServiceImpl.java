package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.UrgeRespDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.UrgeRecord;
import com.sw.ck.bpm.process.mapper.UrgeRecordMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmUrgeService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.SendNotifyCommand;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 催办服务实现。 */
@Service
public class BpmUrgeServiceImpl implements BpmUrgeService {

    private static final Logger log = LoggerFactory.getLogger(BpmUrgeServiceImpl.class);

    static final String RESULT_ACCEPTED = "ACCEPTED";
    static final String RESULT_COOLDOWN = "COOLDOWN";
    static final String RESULT_REJECTED = "REJECTED";

    private static final Duration COOLDOWN = Duration.ofMinutes(10);
    private static final String STATUS_RUNNING = "RUNNING";

    /** 单节点进程内同实例串行：避免并发失败方在 H2/PG 行锁等待上超时 500（跨实例兜底仍靠 DB 行锁）。 */
    private final ConcurrentHashMap<Long, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();

    private final BpmInstanceService bpmInstanceService;
    private final BpmTaskFacade bpmTaskFacade;
    private final UrgeRecordMapper urgeRecordMapper;
    private final NotifyFacade notifyFacade;
    private final com.sw.ck.notify.api.NotifyRoutingService notifyRoutingService;
    private final JdbcTemplate jdbcTemplate;

    public BpmUrgeServiceImpl(BpmInstanceService bpmInstanceService,
                              BpmTaskFacade bpmTaskFacade,
                              UrgeRecordMapper urgeRecordMapper,
                              NotifyFacade notifyFacade,
                              JdbcTemplate jdbcTemplate) {
        this(bpmInstanceService, bpmTaskFacade, urgeRecordMapper, notifyFacade, null, jdbcTemplate);
    }

@org.springframework.beans.factory.annotation.Autowired
    public BpmUrgeServiceImpl(BpmInstanceService bpmInstanceService,
                              BpmTaskFacade bpmTaskFacade,
                              UrgeRecordMapper urgeRecordMapper,
                              NotifyFacade notifyFacade,
                              com.sw.ck.notify.api.NotifyRoutingService notifyRoutingService,
                              JdbcTemplate jdbcTemplate) {
        this.bpmInstanceService = bpmInstanceService;
        this.bpmTaskFacade = bpmTaskFacade;
        this.urgeRecordMapper = urgeRecordMapper;
        this.notifyFacade = notifyFacade;
        this.notifyRoutingService = notifyRoutingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public UrgeRespDTO urge(Long instanceRecordId) {
        return urge(instanceRecordId, null, null);
    }

    @Override
    @Transactional
    public UrgeRespDTO urge(Long instanceRecordId, String message) {
        return urge(instanceRecordId, message, null);
    }

    @Override
    @Transactional
    public UrgeRespDTO urge(Long instanceRecordId, String message, List<Long> recipientIds) {
        String normalizedMessage = normalizeMessage(message);
        LoginUser loginUser = LoginUserHolder.get();
        BpmInstance instance = bpmInstanceService.getById(instanceRecordId);
        if (instance == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在");
        }
        // empty = 实例标识缺失：与“无活动审批任务”同判拒绝（后续拒绝文案按活动任务空处理）
        List<BpmTaskDTO> activeTasks = bpmTaskFacade
                .queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of());
        if (!isLegalUrgeOperator(instance, loginUser, activeTasks)) {
            log.warn("催办越权拒绝: instanceId={}, initiator={}, currentUser={}",
                    instanceRecordId, instance.getInitiatorId(), loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(),
                    "仅发起人或当前待办参与人可催办该实例");
        }
        // 同实例并发催办进程内串行：未及时拿到锁按受控拒绝收敛（不产生 500）
        ReentrantLock lock = instanceLocks.computeIfAbsent(instanceRecordId, k -> new ReentrantLock());
        boolean locked;
        try {
            locked = lock.tryLock(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "催办繁忙，请稍后重试");
        }
        if (!locked) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "该实例正在催办中，请勿重复提交");
        }
        try {
            return doUrge(instance, loginUser, instanceRecordId, normalizedMessage,
                    recipientIds, activeTasks);
        } finally {
            lock.unlock();
        }
    }

    /** 合法催办操作者：实例发起人，或当前活动任务的办理人/候选办理人（服务端以引擎状态为准）。 */
    private boolean isLegalUrgeOperator(BpmInstance instance, LoginUser loginUser,
                                        List<BpmTaskDTO> activeTasks) {
        if (loginUser == null || loginUser.getUserId() == null) return false;
        if (loginUser.getUserId().equals(instance.getInitiatorId())) return true;
        String uid = String.valueOf(loginUser.getUserId());
        for (BpmTaskDTO task : activeTasks) {
            if (uid.equals(task.getAssignee())) return true;
            if (task.getCandidateUserIds() != null && task.getCandidateUserIds().contains(uid)) {
                return true;
            }
        }
        return false;
    }

    private UrgeRespDTO doUrge(BpmInstance instance, LoginUser loginUser, Long instanceRecordId,
                               String normalizedMessage, List<Long> recipientIds,
                               List<BpmTaskDTO> activeTasks) {

        // 实例行加锁串行化同实例并发催办（H2/PG 语义一致），核对在锁内完成。
        // 并发失败方等待锁期间可能触发 H2 锁等待超时（PG 侧为 lock_timeout/锁冲突异常），
        // 这里以有界重试获取锁：获胜方事务很短，重试后失败方进入冷却判定而非 500。
        awaitInstanceRowLock(instanceRecordId);

        if (!STATUS_RUNNING.equals(instance.getStatus())) {
            return reject(instance, loginUser.getUserId(), "实例已结束，不能催办");
        }

        // 冷却判定：同实例最近一次 ACCEPTED 受理时间在 10 分钟内 → 冷却
        UrgeRecord lastAccepted = urgeRecordMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<UrgeRecord>lambdaQuery()
                                .eq(UrgeRecord::getProcessInstanceId, instance.getProcessInstanceId())
                                .eq(UrgeRecord::getResult, RESULT_ACCEPTED)
                                .orderByDesc(UrgeRecord::getCreateTime)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (lastAccepted != null && lastAccepted.getCreateTime() != null) {
            Duration since = Duration.between(lastAccepted.getCreateTime(), LocalDateTime.now());
            if (since.compareTo(COOLDOWN) < 0) {
                long remainSeconds = Math.max(1, COOLDOWN.minus(since).toSeconds());
                UrgeRecord record = record(instance, loginUser.getUserId(), null, RESULT_COOLDOWN,
                        "冷却中，约 " + remainSeconds + " 秒后可再次催办");
                return UrgeRespDTO.builder().result(RESULT_COOLDOWN)
                        .detail("冷却中，约 " + remainSeconds + " 秒后可再次催办")
                        .recordId(record.getId()).build();
            }
        }

        // 服务端核对活动审批任务（任务变化/实例结束以引擎当前状态为准；已在入口查询）
        if (activeTasks.isEmpty()) {
            return reject(instance, loginUser.getUserId(), "当前无活动审批任务，不能催办");
        }
        Set<Long> targets = new LinkedHashSet<>();
        for (BpmTaskDTO task : activeTasks) {
            if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
                try {
                    targets.add(Long.valueOf(task.getAssignee()));
                } catch (NumberFormatException ignored) {
                    // 非数字处理人（外部系统）不发送站内催办
                }
            } else if (task.getCandidateUserIds() != null) {
                task.getCandidateUserIds().forEach(id -> {
                    try {
                        targets.add(Long.valueOf(id));
                    } catch (NumberFormatException ignored) {
                        // 同上
                    }
                });
            }
        }
        if (targets.isEmpty()) {
            return reject(instance, loginUser.getUserId(), "活动任务无待办人，不能催办");
        }

        // 接收人限定：必须是服务端核定的当前实例合法待办人子集；空 = 旧语义（全部待办人）
        List<Long> selectedTargets = new java.util.ArrayList<>(targets);
        if (recipientIds != null && !recipientIds.isEmpty()) {
            Set<Long> requested = new LinkedHashSet<>();
            for (Long id : recipientIds) {
                if (id != null) requested.add(id);
            }
            if (requested.isEmpty() || !targets.containsAll(requested)) {
                log.warn("催办接收人越界拒绝: instanceId={}, requested={}, legal={}",
                        instanceRecordId, requested, targets);
                return reject(instance, loginUser.getUserId(), "催办接收人不在当前实例合法待办范围");
            }
            selectedTargets = new java.util.ArrayList<>(requested);
        }

        // I6 统一投递权威：催办 = TASK_URGE 事件，渠道/订阅由路由裁决（IN_APP 保底），
        // 每次催办具有新的稳定发生标识（冷却窗批次），站内信至少送达。
        Long firstTarget = selectedTargets.iterator().next();
        for (Long target : selectedTargets) {
            java.util.List<NotifyChannel> urgeChannels = notifyRoutingService == null
                    ? java.util.List.of(NotifyChannel.IN_APP)
                    // empty = 事件类型/上下文缺失无法裁决路由：与路由服务缺席同用 IN_APP 保底
                    : notifyRoutingService.channelsFor("TASK_URGE", target)
                            .orElse(java.util.List.of(NotifyChannel.IN_APP));
            for (NotifyChannel channel : urgeChannels) {
                // send 当前契约恒 present（渠道失败以结果状态表达，不以上空表达失败）
                notifyFacade.send(NotifySendRequest.builder()
                        .channel(channel)
                        .recipientId(target)
                        .title("催办提醒")
                        .content(urgeContent(instance.getProcessInstanceId(), normalizedMessage))
                        .bizType(NotifyBizType.WF_TODO)
                        .bizId(instance.getProcessInstanceId())
                        .tenantId(loginUser.getTenantId())
                        .eventType("TASK_URGE")
                        .occurrenceNo(urgeOccurrence())
                        .linkType("WF_PROCESS")
                        .linkId(instance.getProcessInstanceId())
                        .build())
                        .orElseThrow(() -> new IllegalStateException(
                                "NotifyFacade#send 契约恒 present，empty 属契约违约"));
            }
        }

        UrgeRecord record = new UrgeRecord();
        record.setProcessInstanceId(instance.getProcessInstanceId());
        record.setInitiatorId(loginUser.getUserId());
        record.setTargetUserId(firstTarget);
        record.setResult(RESULT_ACCEPTED);
        record.setDetail(urgeDetail(targets, normalizedMessage));
        record.setCooldownKey("t:" + loginUser.getTenantId() + ":i:" + instance.getProcessInstanceId()
                + ":b:" + (System.currentTimeMillis() / 600000L));
        urgeRecordMapper.insert(record);
        log.info("催办已受理: instance={}, operator={}, targets={}",
                instance.getProcessInstanceId(), loginUser.getUserId(), targets);
        return UrgeRespDTO.builder().result(RESULT_ACCEPTED)
                .detail(urgeDetail(targets, normalizedMessage)).recordId(record.getId()).build();
    }

    /** 留言规范化：去空白；超长（>200）拒绝，避免通知与留痕被超长内容撑爆。 */
    private String normalizeMessage(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 200) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "催办留言最长 200 字");
        }
        return trimmed;
    }

    private String urgeContent(String processInstanceId, String message) {
        String base = "您有待办任务被催办：实例 " + processInstanceId;
        return message == null ? base : base + "；留言：" + message;
    }

    private String urgeDetail(Set<Long> targets, String message) {
        String base = "已通知待办人: " + targets;
        return message == null ? base : base + "；留言：" + message;
    }

    /** 同一冷却窗口内并发催办共享同一发生次序（10 分钟窗，与既有冷却唯一键同粒度）。 */
    private Long urgeOccurrence() {
        return System.currentTimeMillis() / 600000L;
    }

    private UrgeRespDTO reject(BpmInstance instance, Long operator, String reason) {
        UrgeRecord record = record(instance, operator, null, RESULT_REJECTED, reason);
        return UrgeRespDTO.builder().result(RESULT_REJECTED).detail(reason).recordId(record.getId()).build();
    }

    /** 有界重试获取实例行锁：每次失败短暂退避后重试，耗尽后按数据库异常向上抛（真实失败）。 */
    private void awaitInstanceRowLock(Long instanceRecordId) {
        final int maxAttempts = 20;
        final long backoffMillis = 100L;
        for (int attempt = 1; ; attempt++) {
            try {
                jdbcTemplate.queryForList("SELECT id FROM sw_bpm_instance WHERE id = ? FOR UPDATE",
                        instanceRecordId);
                return;
            } catch (org.springframework.dao.DataAccessException e) {
                if (attempt >= maxAttempts) {
                    log.warn("催办行锁获取失败(重试耗尽): instance={}, attempts={}", instanceRecordId, attempt);
                    throw e;
                }
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private UrgeRecord record(BpmInstance instance, Long operator, Long target, String result, String detail) {
        UrgeRecord record = new UrgeRecord();
        record.setProcessInstanceId(instance.getProcessInstanceId());
        record.setInitiatorId(operator);
        record.setTargetUserId(target);
        record.setResult(result);
        record.setDetail(detail);
        urgeRecordMapper.insert(record);
        return record;
    }
}
