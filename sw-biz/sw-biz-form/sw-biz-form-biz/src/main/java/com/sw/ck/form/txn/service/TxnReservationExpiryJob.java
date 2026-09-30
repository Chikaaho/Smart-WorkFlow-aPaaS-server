package com.sw.ck.form.txn.service;

import com.sw.ck.common.constant.CommonConstants;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.txn.entity.TxnReservationEntity;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 预占过期释放扫描（每 60 秒，单轮有界 100 条）。
 * <p>跨租户只读扫描用裸 SQL（此处为平台固定表，非动态宽表）；结算时按行还原权威租户，
 * 由「状态=ACTIVE 且 expires_at<=now」的条件更新裁决唯一结算，与人工确认互斥。</p>
 */
@Component
public class TxnReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(TxnReservationExpiryJob.class);

    private static final int MAX_BATCH = 100;
    private static final String SCAN_SQL = """
            SELECT id, tenant_id, action_id, action_version, form_id, record_id, quantity, expires_at
            FROM sw_form_txn_reservation
            WHERE status = 'ACTIVE' AND deleted = 0 AND expires_at <= ?
            ORDER BY expires_at
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TxnActionTxOperations txOps;
    private final FormIdGenerator idGenerator;

    public TxnReservationExpiryJob(JdbcTemplate jdbcTemplate,
                                   TxnActionTxOperations txOps,
                                   FormIdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.txOps = txOps;
        this.idGenerator = idGenerator;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sweep() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(SCAN_SQL, Timestamp.valueOf(LocalDateTime.now()), MAX_BATCH);
        } catch (RuntimeException e) {
            log.error("预占过期扫描失败：{}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        int settled = 0;
        for (Map<String, Object> row : rows) {
            try {
                if (settleOne(row)) {
                    settled++;
                }
            } catch (Exception e) {
                log.error("预占过期结算失败 reservationId={}: {}", row.get("id"), e.getMessage());
            }
        }
        if (settled > 0) {
            log.info("预占过期释放完成：本轮结算 {} 条", settled);
        }
    }

    private boolean settleOne(Map<String, Object> row) {
        TxnReservationEntity reservation = new TxnReservationEntity();
        reservation.setId(String.valueOf(row.get("id")));
        reservation.setTenantId(((Number) row.get("tenant_id")).longValue());
        reservation.setActionId(String.valueOf(row.get("action_id")));
        reservation.setActionVersion(((Number) row.get("action_version")).intValue());
        reservation.setFormId(String.valueOf(row.get("form_id")));
        reservation.setRecordId(String.valueOf(row.get("record_id")));
        reservation.setQuantity((java.math.BigDecimal) row.get("quantity"));
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
    private boolean withSettlementTenant(Long tenantId, java.util.function.Supplier<Boolean> action) {
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
