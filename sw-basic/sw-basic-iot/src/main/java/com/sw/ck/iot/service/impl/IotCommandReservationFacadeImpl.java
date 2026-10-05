package com.sw.ck.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.iot.entity.IotCommandReservation;
import com.sw.ck.iot.mapper.IotCommandReservationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * IoT 命令预约门面实现（P63）。
 * <p>
 * 幂等身份 =（租户, 流程实例）唯一键：重复成功事件命中既有意图返回既有 ID。
 * 创建时刻预约时刻已过（含迟到窗口）→ 保留记录置 EXPIRED，不立即补发；
 * 取消为条件更新（仅 PENDING），与到点认领竞争只一个结果生效并留审计。
 * </p>
 */
@Slf4j
@Service
public class IotCommandReservationFacadeImpl implements IotCommandReservationFacade {

    private final IotCommandReservationMapper mapper;

    public IotCommandReservationFacadeImpl(IotCommandReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> createIntent(Long tenantId, String processInstanceId, String processDefKey,
                                       Integer defVersion, String formKey, String recordId,
                                       String deviceKey, String productId, String deviceName,
                                       String commandKey, String commandType, String payloadJson,
                                       LocalDateTime dueAtUtc, String timezoneId,
                                       String dueLocalText, int lateWindowSeconds) {
        if (tenantId == null || processInstanceId == null || processInstanceId.isBlank()) {
            throw new BaseException(400, "预约意图缺少租户/流程实例身份");
        }
        IotCommandReservation existing = mapper.selectByInstance(tenantId, processInstanceId);
        if (existing != null) {
            log.info("预约意图幂等命中，复用既有预约: id={}, processInstanceId={}",
                    existing.getId(), processInstanceId);
            return Optional.of(existing.getId());
        }
        IotCommandReservation row = new IotCommandReservation();
        row.setTenantId(tenantId);
        row.setProcessInstanceId(processInstanceId);
        row.setProcessDefKey(processDefKey);
        row.setDefVersion(defVersion);
        row.setFormKey(formKey);
        row.setRecordId(recordId);
        row.setDeviceKey(deviceKey);
        row.setProductId(productId);
        row.setDeviceName(deviceName);
        row.setCommandKey(commandKey);
        row.setCommandType(commandType == null ? "PROPERTY" : commandType);
        row.setPayload(payloadJson);
        row.setDueAtUtc(dueAtUtc);
        row.setTimezoneId(timezoneId);
        row.setDueLocalText(dueLocalText);
        row.setLateWindowSeconds(lateWindowSeconds);
        // 创建时刻预约时刻已过（含迟到窗口）：保留关联记录并标为过期，不立即补发
        LocalDateTime windowEnd = dueAtUtc.plusSeconds(Math.max(0, lateWindowSeconds));
        row.setStatus(windowEnd.isBefore(LocalDateTime.now()) ? "EXPIRED" : "PENDING");
        mapper.insert(row);
        return Optional.of(row.getId());
    }

    @Override
    public Optional<String> cancel(Long tenantId, Long reservationId,
                                   Long actorUserId, String reason) {
        if (tenantId == null || reservationId == null) {
            return Optional.empty();
        }
        IotCommandReservation reservation = mapper.selectById(reservationId);
        if (reservation == null || !tenantId.equals(reservation.getTenantId())
                || Integer.valueOf(1).equals(reservation.getDeleted())) {
            return Optional.empty();
        }
        int updated = mapper.update(null, new LambdaUpdateWrapper<IotCommandReservation>()
                .set(IotCommandReservation::getStatus, "CANCELED")
                .set(IotCommandReservation::getCancelBy, actorUserId)
                .set(IotCommandReservation::getCancelReason, reason)
                .set(IotCommandReservation::getCancelTime, LocalDateTime.now())
                .set(IotCommandReservation::getUpdateTime, LocalDateTime.now())
                .eq(IotCommandReservation::getId, reservationId)
                .eq(IotCommandReservation::getStatus, "PENDING")
                .eq(IotCommandReservation::getDeleted, 0));
        return Optional.of(updated == 1 ? CANCEL_CANCELED : CANCEL_NOT_CANCELLABLE);
    }

    @Override
    public Optional<List<IotReservationView>> findByProcessInstance(Long tenantId, String processInstanceId) {
        if (tenantId == null || processInstanceId == null || processInstanceId.isBlank()) {
            return Optional.of(List.of());
        }
        IotCommandReservation row = mapper.selectByInstance(tenantId, processInstanceId);
        return Optional.of(row == null ? List.of() : List.of(toView(row)));
    }

    @Override
    public Optional<IotReservationView> find(Long tenantId, Long reservationId) {
        if (tenantId == null || reservationId == null) {
            return Optional.empty();
        }
        IotCommandReservation row = mapper.selectById(reservationId);
        if (row == null || !tenantId.equals(row.getTenantId())
                || Integer.valueOf(1).equals(row.getDeleted())) {
            return Optional.empty();
        }
        return Optional.of(toView(row));
    }

    private IotReservationView toView(IotCommandReservation row) {
        return new IotReservationView(row.getId(), row.getTenantId(), row.getProcessInstanceId(),
                row.getProcessDefKey(), row.getDefVersion(), row.getFormKey(), row.getRecordId(),
                row.getDeviceKey(), row.getProductId(), row.getDeviceName(), row.getCommandKey(),
                row.getCommandType(), row.getPayload(), row.getDueAtUtc(), row.getTimezoneId(),
                row.getDueLocalText(), row.getLateWindowSeconds() == null ? 0 : row.getLateWindowSeconds(),
                row.getStatus(), row.getCommandId(), row.getRejectReason(),
                row.getCancelBy(), row.getCancelReason(), row.getCancelTime(), row.getCreateTime());
    }
}
