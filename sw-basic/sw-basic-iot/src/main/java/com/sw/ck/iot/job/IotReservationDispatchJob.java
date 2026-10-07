package com.sw.ck.iot.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sw.ck.iot.entity.IotCommandReservation;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.mapper.IotCommandReservationMapper;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.IotDeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * IoT 预约到点认领下发调度（P63，DB 认领模式：重启自然恢复，无内存状态）。
 * <p>
 * 三步有界循环：①超过迟到窗口仍未认领的 PENDING → EXPIRED（过期不外发，对账先）；
 * ②认领路径对已过窗口仍未对账的 PENDING 直接拒绝（PENDING→EXPIRED，认领先守卫，
 * 防对账滞后竞态窗口外认领）；③进入窗口 [due, due+window] 的 PENDING 条件认领 →
 * DISPATCHING（取消与认领竞争只一个生效），到点重核设备权威与已发布功能有效性后
 * 入队既有点位命令并将其过期时间对齐预约窗口（窗口覆盖受理与实际外发边界，窗口内
 * 只入队不无限期发送），随后立即尝试外发；发送失败回退既有可重试/补偿语义，回执
 * 超时/UNKNOWN 沿既有边界不变。时钟经注入 Clock 供给，受控验证可贯穿真实入口。
 * </p>
 */
@Slf4j
@Component
public class IotReservationDispatchJob {

    private final IotCommandReservationMapper reservationMapper;
    private final IotDeviceCommandMapper commandMapper;
    private final IotDeviceService deviceService;
    private final ObjectProvider<com.sw.ck.iot.util.DeferredControlUtil> senderProvider;
    private final CommandQueueService commandQueueService;
    private final Clock clock;

    public IotReservationDispatchJob(IotCommandReservationMapper reservationMapper,
                                     IotDeviceCommandMapper commandMapper,
                                     IotDeviceService deviceService,
                                     ObjectProvider<com.sw.ck.iot.util.DeferredControlUtil> senderProvider,
                                     CommandQueueService commandQueueService,
                                     Clock clock) {
        this.reservationMapper = reservationMapper;
        this.commandMapper = commandMapper;
        this.deviceService = deviceService;
        this.senderProvider = senderProvider;
        this.commandQueueService = commandQueueService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 10_000L)
    public void dispatchDueReservations() {
        // 调度线程无登录态：挂起租户拦截器，行自身 tenant_id 为权威过滤（显式条件）
        com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspend();
        try {
            LocalDateTime nowUtc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
            expireOverdue(nowUtc);
            List<IotCommandReservation> due =
                    reservationMapper.selectDuePending(nowUtc.minusSeconds(3600), nowUtc);
            for (IotCommandReservation reservation : due) {
                claimAndDispatch(reservation, nowUtc);
            }
        } finally {
            com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.restore();
        }
    }

    private void expireOverdue(LocalDateTime nowUtc) {
        List<IotCommandReservation> overdue = reservationMapper.selectExpiredPending(nowUtc);
        for (IotCommandReservation reservation : overdue) {
            LocalDateTime windowEnd = reservation.getDueAtUtc()
                    .plusSeconds(reservation.getLateWindowSeconds() == null ? 0
                            : reservation.getLateWindowSeconds());
            if (windowEnd.isBefore(nowUtc)) {
                int updated = reservationMapper.update(null, new LambdaUpdateWrapper<IotCommandReservation>()
                        .set(IotCommandReservation::getStatus, "EXPIRED")
                        .set(IotCommandReservation::getUpdateTime, LocalDateTime.now())
                        .eq(IotCommandReservation::getId, reservation.getId())
                        .eq(IotCommandReservation::getStatus, "PENDING")
                        .eq(IotCommandReservation::getDeleted, 0));
                if (updated == 1) {
                    log.info("预约超过迟到窗口，标记过期（不外发）: id={}, processInstanceId={}",
                            reservation.getId(), reservation.getProcessInstanceId());
                }
            }
        }
    }

    private void claimAndDispatch(IotCommandReservation reservation, LocalDateTime nowUtc) {
        com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspend();
        try {
            claimAndDispatchSuspended(reservation, nowUtc);
        } finally {
            com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.restore();
        }
    }

    private void claimAndDispatchSuspended(IotCommandReservation reservation, LocalDateTime nowUtc) {
        // 认领先守卫（P63 §4.2）：对账滞后时窗后 PENDING 也不得经认领路径下发
        LocalDateTime windowEnd = reservation.getDueAtUtc()
                .plusSeconds(reservation.getLateWindowSeconds() == null ? 0
                        : reservation.getLateWindowSeconds());
        if (!nowUtc.isBefore(windowEnd)) {
            int expired = reservationMapper.update(null, new LambdaUpdateWrapper<IotCommandReservation>()
                    .set(IotCommandReservation::getStatus, "EXPIRED")
                    .set(IotCommandReservation::getUpdateTime, LocalDateTime.now())
                    .eq(IotCommandReservation::getId, reservation.getId())
                    .eq(IotCommandReservation::getStatus, "PENDING")
                    .eq(IotCommandReservation::getDeleted, 0));
            if (expired == 1) {
                log.info("认领入口拒绝窗后预约（未开始合法下发即过期，零外发）: id={}, windowEndUtc={}, nowUtc={}",
                        reservation.getId(), windowEnd, nowUtc);
            }
            return;
        }
        int claimed = reservationMapper.update(null, new LambdaUpdateWrapper<IotCommandReservation>()
                .set(IotCommandReservation::getStatus, "DISPATCHING")
                .set(IotCommandReservation::getUpdateTime, LocalDateTime.now())
                .eq(IotCommandReservation::getId, reservation.getId())
                .eq(IotCommandReservation::getStatus, "PENDING")
                .eq(IotCommandReservation::getDeleted, 0));
        if (claimed != 1) {
            return; // 已被取消/过期：竞争只有一个结果生效
        }
        try {
            dispatch(reservation, nowUtc);
        } catch (Exception e) {
            log.error("预约认领下发失败，标记 FAILED 可查: id={}, processInstanceId={}",
                    reservation.getId(), reservation.getProcessInstanceId(), e);
            reservationMapper.update(null, new LambdaUpdateWrapper<IotCommandReservation>()
                    .set(IotCommandReservation::getStatus, "FAILED")
                    .set(IotCommandReservation::getRejectReason, truncate(e.getMessage()))
                    .set(IotCommandReservation::getUpdateTime, LocalDateTime.now())
                    .eq(IotCommandReservation::getId, reservation.getId())
                    .eq(IotCommandReservation::getStatus, "DISPATCHING")
                    .eq(IotCommandReservation::getDeleted, 0));
        }
    }

    private void dispatch(IotCommandReservation reservation, LocalDateTime nowUtc) {
        LocalDateTime windowEnd = reservation.getDueAtUtc()
                .plusSeconds(reservation.getLateWindowSeconds() == null ? 0
                        : reservation.getLateWindowSeconds());
        // 到点权威重核（P63 G03a 确证缺陷修复）：无论来源是否在受理时冻结 product/name，
        // 一律按 deviceKey+预约租户重解析执行目标——设备失效/改属他租户在到点拒绝并可查，
        // 不按冻结值跨租户入队（FORM_FIELD/VARIABLE 的既有设计语义统一到全部来源）。
        com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget target =
                deviceService.resolveDeviceTargetByKey(reservation.getTenantId(), reservation.getDeviceKey());
        if (target == null) {
            throw new IllegalStateException("设备目标已失效或跨租户: deviceKey=" + reservation.getDeviceKey());
        }
        // 到点功能有效性重核（P63 §4.3）：以冻结的功能键+类型对照当前已发布物模型，
        // 未配置/未发布/未声明 fail closed → FAILED 可查、零外发、审批结果不回滚。
        deviceService.validatePublishedFunction(reservation.getTenantId(), reservation.getDeviceKey(),
                reservation.getCommandType(), reservation.getCommandKey());
        String productId = target.productId();
        String deviceName = target.deviceName();
        // 入队（设备不存在/租户不符由入队路径抛出 → FAILED 可查）
        IotDeviceCommand command = deviceService.dispatchCommandIdempotent(
                productId, deviceName,
                reservation.getCommandKey(), reservation.getCommandType(),
                reservation.getPayload(), reservation.getProcessInstanceId(),
                "RESERVATION:" + reservation.getId() + ":" + reservation.getDeviceName()
                        + ":" + reservation.getCommandKey());
        // 命令有效期对齐预约窗口：窗口外不再外发（含补偿重试路径）。
        // 以窗口终点的绝对时刻按系统时区落列，与补偿过滤/补发守卫的本地钟比较同口径，
        // 避免预约命令（UTC 语义）与既有命令（本地钟 +24h）在比较点混用两种时区。
        commandMapper.update(null, new LambdaUpdateWrapper<IotDeviceCommand>()
                .set(IotDeviceCommand::getExpiryTime,
                        windowEnd.atOffset(ZoneOffset.UTC).toInstant().atZone(ZoneId.systemDefault())
                                .toLocalDateTime())
                .set(IotDeviceCommand::getUpdateTime, LocalDateTime.now())
                .eq(IotDeviceCommand::getId, command.getId())
                .eq(IotDeviceCommand::getStatus, "QUEUED"));

        // 立即尝试外发：窗口内不得只入队后无限期等待补偿。
        // 状态迁移（QUEUED→SENDING）由共享发送路径 markSending 统一承载；
        // 此处不得预占 SENDING，否则 sendCommand 内的 markSending 必然撞状态判死。
        IotDeviceCommand queued = commandMapper.selectById(command.getId());
        com.sw.ck.iot.util.DeferredControlUtil sender = senderProvider.getIfAvailable();
        if (sender != null && queued != null) {
            sender.sendCommand(queued);
        } else if (queued != null) {
            // 发送通道未装配：回退可重试失败（既有语义），由补偿在窗口内重试
            commandQueueService.markFailed(queued.getId(), "发送通道未装配（sw.iot.enabled=false）");
        }
        int dispatched = reservationMapper.update(null, new LambdaUpdateWrapper<IotCommandReservation>()
                .set(IotCommandReservation::getStatus, "DISPATCHED")
                .set(IotCommandReservation::getCommandId, command.getId())
                .set(IotCommandReservation::getUpdateTime, LocalDateTime.now())
                .eq(IotCommandReservation::getId, reservation.getId())
                .eq(IotCommandReservation::getStatus, "DISPATCHING")
                .eq(IotCommandReservation::getDeleted, 0));
        if (dispatched == 1) {
            log.info("预约已到点下发: reservationId={}, commandId={}, processInstanceId={}, dueUtc={}, window={}s",
                    reservation.getId(), command.getId(), reservation.getProcessInstanceId(),
                    reservation.getDueAtUtc(), reservation.getLateWindowSeconds());
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

}
