package com.sw.ck.form.txn.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.constant.CommonConstants;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.txn.entity.TxnReservationEntity;
import com.sw.ck.form.txn.mapper.TxnReservationMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import lombok.extern.log4j.Log4j2;
/**
 * 预占过期释放扫描（每 60 秒，单轮有界 100 条）。
 * <p>调度线程无登录态：跨租户只读扫描按既有调度任务同口径挂起租户过滤
 * （{@link TenantLineSuspension}，与命令调度/IoT 触发恢复一致，孤儿预占为全局对象）；
 * 结算时按行还原权威租户，由「状态=ACTIVE 且 expires_at<=now」的条件更新裁决唯一结算，
 * 与人工确认互斥。</p>
 */
@Component
@Log4j2
public class TxnReservationExpiryJob {

    private static final long MAX_BATCH = 100L;

    @Autowired
    private TxnReservationMapper reservationMapper;
    @Autowired
    private TxnActionTxOperations txOps;
    @Autowired
    private FormIdGenerator idGenerator;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sweep() {
        List<TxnReservationEntity> due;
        try {
            LocalDateTime now = LocalDateTime.now();
            try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
                due = reservationMapper.selectList(Wrappers.<TxnReservationEntity>lambdaQuery()
                        .eq(TxnReservationEntity::getStatus, "ACTIVE")
                        .le(TxnReservationEntity::getExpiresAt, now)
                        .orderByAsc(TxnReservationEntity::getExpiresAt)
                        .last("LIMIT " + MAX_BATCH));
            }
        } catch (RuntimeException e) {
            log.error("预占过期扫描失败：{}", e.getMessage());
            return;
        }
        if (due == null || due.isEmpty()) {
            return;
        }
        int settled = 0;
        for (TxnReservationEntity reservation : due) {
            try {
                if (settleOne(reservation)) {
                    settled++;
                }
            } catch (Exception e) {
                log.error("预占过期结算失败 reservationId={}: {}", reservation.getId(), e.getMessage());
            }
        }
        if (settled > 0) {
            log.info("预占过期释放完成：本轮结算 {} 条", settled);
        }
    }

    private boolean settleOne(TxnReservationEntity reservation) {
        String invocationId = idGenerator.generate();
        String invocationKey = "EXPIRE:" + reservation.getId();
        String requestHash = sha256("EXPIRE|" + reservation.getId());
        return withSettlementTenant(reservation.getTenantId(),
                () -> {
                    try {
                        return txOps.settleExpired(reservation, invocationId, invocationKey, requestHash,
                                CommonConstants.SYSTEM_OPERATOR_ID);
                    } catch (DuplicateKeyException duplicate) {
                        log.debug("预占已由其他路径结算 reservationId={}", reservation.getId());
                        return false;
                    }
                });
    }

    /**
     * 调度线程按行还原权威租户建立执行边界（与命令补偿任务同一模式；
     * 不使用租户挂起，避免抹掉多租户约束）。
     */
    private boolean withSettlementTenant(Long tenantId, Supplier<Boolean> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser identity = new LoginUser();
        identity.setTenantId(tenantId == null ? 0L : tenantId);
        identity.setUserId(CommonConstants.SYSTEM_OPERATOR_ID);
        identity.setUsername("txn-reservation-expiry");
        try {
            LoginUserHolder.set(identity);
            return Boolean.TRUE.equals(action.get());
        } finally {
            if (previous == null) {
                LoginUserHolder.clear();
            } else {
                LoginUserHolder.set(previous);
            }
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
