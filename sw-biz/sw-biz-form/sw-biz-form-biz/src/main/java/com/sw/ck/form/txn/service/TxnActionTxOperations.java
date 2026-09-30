package com.sw.ck.form.txn.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dynamic.DynamicTableSql;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.service.FormFieldValidator;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.txn.entity.TxnActionEntity;
import com.sw.ck.form.txn.entity.TxnActionVersionEntity;
import com.sw.ck.form.txn.entity.TxnInvocationEntity;
import com.sw.ck.form.txn.entity.TxnLedgerEntity;
import com.sw.ck.form.txn.entity.TxnReservationEntity;
import com.sw.ck.form.txn.mapper.TxnInvocationMapper;
import com.sw.ck.form.txn.mapper.TxnLedgerMapper;
import com.sw.ck.form.txn.mapper.TxnReservationMapper;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 事务动作执行内核（全部方法在事务边界内运行）。
 * <p>写入次序统一为：父行锁 → 条件更新（版本/可用量/状态守卫）→ 预占凭据 → 台账 → 调用记录（SUCCEEDED）。</p>
 * <p>业务拒绝通过 {@link TxnBusinessRejection} 抛出并回滚半成品写入，由外层执行器另事务记录 REJECTED。</p>
 */
@Service
public class TxnActionTxOperations {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_RELEASED = "RELEASED";
    private static final String STATUS_EXPIRED = "EXPIRED";

    /** DECIMAL(20,6) 可安全容纳的绝对值上限（保守取值，防溢出）。 */
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("100000000000000");

    private final TxnInvocationMapper invocationMapper;
    private final TxnReservationMapper reservationMapper;
    private final TxnLedgerMapper ledgerMapper;
    private final com.sw.ck.form.txn.mapper.TxnActionVersionMapper versionMapper;
    private final TxnFormBinding binding;
    private final FormIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public TxnActionTxOperations(TxnInvocationMapper invocationMapper,
                                 TxnReservationMapper reservationMapper,
                                 TxnLedgerMapper ledgerMapper,
                                 com.sw.ck.form.txn.mapper.TxnActionVersionMapper versionMapper,
                                 TxnFormBinding binding,
                                 FormIdGenerator idGenerator,
                                 ObjectMapper objectMapper,
                                 JdbcTemplate jdbcTemplate) {
        this.invocationMapper = invocationMapper;
        this.reservationMapper = reservationMapper;
        this.ledgerMapper = ledgerMapper;
        this.versionMapper = versionMapper;
        this.binding = binding;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 目标记录快照 ====================

    /** 目标行快照（父行锁后读取）。 */
    private record RowSnapshot(BigDecimal balance, BigDecimal reserved, Long version,
                               Map<String, Object> keyValues) {
    }

    private record Columns(String table, String balanceCol, String reservedCol, List<String> keyCols) {
    }

    private Columns resolveColumns(FormDefEntity form, TxnActionConfig cfg) {
        Map<String, FormFieldValidator.FieldDef> defs = binding.fieldDefs(form.getId());
        String balanceCol = binding.requireNumberColumn(defs, cfg.getBalanceField(), "余额");
        String reservedCol = cfg.getReservedField() == null || cfg.getReservedField().isBlank()
                ? null : binding.requireNumberColumn(defs, cfg.getReservedField(), "预占");
        List<String> keyCols = binding.resolveKeyColumns(defs, cfg.getKeyFields());
        return new Columns(form.getPhysicalTableName(), balanceCol, reservedCol, keyCols);
    }

    private RowSnapshot lockAndRead(String formId, FormDefEntity form, Columns cols, String recordId, Long tenantId) {
        boolean locked = DynamicTableSql.tryLockLiveRow(jdbcTemplate, cols.table(), recordId, tenantId);
        if (!locked) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_TARGET_NOT_FOUND,
                    "动作目标记录不存在或已删除：" + recordId);
        }
        StringBuilder select = new StringBuilder("SELECT ")
                .append(DynamicTableSql.quote("version")).append(", ")
                .append(DynamicTableSql.quote(cols.balanceCol()));
        if (cols.reservedCol() != null) {
            select.append(", ").append(DynamicTableSql.quote(cols.reservedCol()));
        }
        for (String k : cols.keyCols()) {
            select.append(", ").append(DynamicTableSql.quote(k));
        }
        select.append(" FROM ").append(DynamicTableSql.quote(cols.table()))
                .append(" WHERE ").append(DynamicTableSql.quote("id")).append(" = ?")
                .append(" AND ").append(DynamicTableSql.quote("deleted")).append(" = 0")
                .append(" AND ").append(DynamicTableSql.quote("tenant_id")).append(" = ?");
        List<Map<String, Object>> rows = DynamicTableSql.query(jdbcTemplate, cols.table(),
                select.toString(), recordId, tenantId);
        if (rows.isEmpty()) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_TARGET_NOT_FOUND,
                    "动作目标记录不存在或已删除：" + recordId);
        }
        Map<String, Object> row = rows.get(0);
        BigDecimal balance = toDecimal(getVal(row, cols.balanceCol()));
        BigDecimal reserved = cols.reservedCol() == null
                ? BigDecimal.ZERO : toDecimal(getVal(row, cols.reservedCol()));
        Object versionRaw = getVal(row, "version");
        Long version = versionRaw == null ? null : ((Number) versionRaw).longValue();
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String k : cols.keyCols()) {
            keys.put(k, getVal(row, k));
        }
        return new RowSnapshot(balance, reserved, version, keys);
    }

    // ==================== 入口 ====================

    @Transactional(rollbackFor = Exception.class)
    public TxnInvokeResult reserve(TxnActionEntity action, TxnActionVersionEntity version, TxnActionConfig cfg,
                                   TxnInvokeRequest req, String invocationId, String invocationKey,
                                   String requestHash) {
        long start = System.currentTimeMillis();
        FormDefEntity form = binding.requirePublishedForm(action.getFormId());
        Long tenantId = currentTenantId();
        Long userId = currentUserId();
        Columns cols = resolveColumns(form, cfg);
        if (cols.reservedCol() == null) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_FIELD_BINDING_INVALID, "RESERVE 需要预占字段绑定");
        }
        BigDecimal qty = requirePositiveQuantity(req.getQuantity(), cfg);
        if (req.getExpectedVersion() != null && req.getExpectedVersion() < 0) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_VERSION_CONFLICT, "期望版本不合法");
        }

        RowSnapshot before = lockAndRead(action.getFormId(), form, cols, req.getRecordId(), tenantId);
        checkVersion(req, before);
        verifyBusinessKeys(req, before);

        boolean nonNegative = cfg.getNonNegativeAvailable() == null || Boolean.TRUE.equals(cfg.getNonNegativeAvailable());
        StringBuilder sql = new StringBuilder("UPDATE ").append(DynamicTableSql.quote(cols.table()))
                .append(" SET ").append(DynamicTableSql.quote(cols.reservedCol())).append(" = ")
                .append(DynamicTableSql.quote(cols.reservedCol())).append(" + ?, ")
                .append(DynamicTableSql.quote("version")).append(" = ").append(DynamicTableSql.quote("version")).append(" + 1, ")
                .append(DynamicTableSql.quote("update_time")).append(" = ?, ")
                .append(DynamicTableSql.quote("update_by")).append(" = ?")
                .append(" WHERE ").append(DynamicTableSql.quote("id")).append(" = ?")
                .append(" AND ").append(DynamicTableSql.quote("deleted")).append(" = 0")
                .append(" AND ").append(DynamicTableSql.quote("tenant_id")).append(" = ?");
        List<Object> params = new ArrayList<>(List.of(qty, LocalDateTime.now(), userIdOrSystem(userId),
                req.getRecordId(), tenantId));
        if (nonNegative) {
            sql.append(" AND (").append(DynamicTableSql.quote(cols.balanceCol())).append(" - ")
                    .append(DynamicTableSql.quote(cols.reservedCol())).append(") >= ?");
            params.add(qty);
        }
        int updated = DynamicTableSql.update(jdbcTemplate, cols.table(), sql.toString(), params.toArray());
        if (updated != 1) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_INSUFFICIENT_AVAILABLE,
                    "可用数量不足（可用=余额-有效预占），预占被拒绝");
        }
        RowSnapshot after = readOnly(cols, req.getRecordId(), tenantId);

        long expiresIn = cfg.getExpiresInSeconds() == null ? 0 : cfg.getExpiresInSeconds();
        LocalDateTime now = LocalDateTime.now();
        TxnReservationEntity reservation = new TxnReservationEntity();
        reservation.setId(idGenerator.generate());
        reservation.setActionId(action.getId());
        reservation.setActionVersion(version.getVersionNo());
        reservation.setFormId(form.getId());
        reservation.setRecordId(req.getRecordId());
        reservation.setBizKeysJson(writeJson(before.keyValues()));
        reservation.setQuantity(qty);
        reservation.setStatus(STATUS_ACTIVE);
        reservation.setExpiresAt(now.plusSeconds(expiresIn));
        reservation.setReserveInvocationId(invocationId);
        if (userId != null) {
            reservation.setCreateBy(userId);
            reservation.setUpdateBy(userId);
        }
        reservationMapper.insert(reservation);

        insertLedger(action, version, invocationId, reservation.getId(), "RESERVE", form.getId(),
                req.getRecordId(), qty, after, before.keyValues(), userId);
        insertInvocation(action, version, invocationId, invocationKey, requestHash, req.getRecordId(),
                "SUCCEEDED", null, null, successResultJson(reservation.getId(), qty, after),
                System.currentTimeMillis() - start, userId);
        return new TxnInvokeResult(invocationId, "SUCCEEDED", version.getVersionNo(), reservation.getId(),
                qty, after.balance(), after.reserved(), null, null,
                System.currentTimeMillis() - start, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public TxnInvokeResult confirm(TxnActionEntity action, TxnActionVersionEntity version, TxnActionConfig cfg,
                                   TxnInvokeRequest req, String invocationId, String invocationKey,
                                   String requestHash) {
        long start = System.currentTimeMillis();
        return settle(action, version, cfg, req, invocationId, invocationKey, requestHash, true, start);
    }

    @Transactional(rollbackFor = Exception.class)
    public TxnInvokeResult release(TxnActionEntity action, TxnActionVersionEntity version, TxnActionConfig cfg,
                                   TxnInvokeRequest req, String invocationId, String invocationKey,
                                   String requestHash) {
        long start = System.currentTimeMillis();
        return settle(action, version, cfg, req, invocationId, invocationKey, requestHash, false, start);
    }

    private TxnInvokeResult settle(TxnActionEntity action, TxnActionVersionEntity version, TxnActionConfig cfg,
                                   TxnInvokeRequest req, String invocationId, String invocationKey,
                                   String requestHash, boolean confirm, long start) {
        FormDefEntity form = binding.requirePublishedForm(action.getFormId());
        Long tenantId = currentTenantId();
        Long userId = currentUserId();
        Columns cols = resolveColumns(form, cfg);
        if (cols.reservedCol() == null) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                    (confirm ? "CONFIRM" : "RELEASE") + " 需要预占字段绑定");
        }
        TxnReservationEntity reservation = req.getReservationId() == null ? null
                : reservationMapper.selectById(req.getReservationId());
        if (reservation == null) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_RESERVATION_NOT_FOUND, "预占凭据不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        // 状态守卫：确认要求未过期（与过期释放竞争仅一次合法结算）；释放允许对未结算的任意 ACTIVE 生效
        var claim = Wrappers.<TxnReservationEntity>lambdaUpdate()
                .eq(TxnReservationEntity::getId, reservation.getId())
                .eq(TxnReservationEntity::getStatus, STATUS_ACTIVE)
                .set(TxnReservationEntity::getStatus, confirm ? STATUS_CONFIRMED : STATUS_RELEASED)
                .set(TxnReservationEntity::getSettleInvocationId, invocationId)
                .set(TxnReservationEntity::getSettledAt, now)
                .set(TxnReservationEntity::getUpdateTime, now);
        if (confirm) {
            claim.gt(TxnReservationEntity::getExpiresAt, now);
        }
        int claimed = reservationMapper.update(null, claim);
        if (claimed == 0) {
            TxnReservationEntity latest = reservationMapper.selectById(reservation.getId());
            String status = latest == null ? null : latest.getStatus();
            if (status != null && !STATUS_ACTIVE.equals(status)) {
                throw new TxnBusinessRejection(FormErrorCode.ACTION_RESERVATION_NOT_ACTIVE,
                        "预占凭据已结算或已释放（当前状态 " + status + "），不能重复操作");
            }
            throw new TxnBusinessRejection(FormErrorCode.ACTION_RESERVATION_EXPIRED,
                    "预占已过期，不能确认");
        }

        BigDecimal qty = reservation.getQuantity();
        StringBuilder sql = new StringBuilder("UPDATE ").append(DynamicTableSql.quote(cols.table()))
                .append(" SET ");
        List<Object> params = new ArrayList<>();
        if (confirm) {
            sql.append(DynamicTableSql.quote(cols.balanceCol())).append(" = ")
                    .append(DynamicTableSql.quote(cols.balanceCol())).append(" - ?, ");
            params.add(qty);
        }
        sql.append(DynamicTableSql.quote(cols.reservedCol())).append(" = ")
                .append(DynamicTableSql.quote(cols.reservedCol())).append(" - ?, ")
                .append(DynamicTableSql.quote("version")).append(" = ").append(DynamicTableSql.quote("version")).append(" + 1, ")
                .append(DynamicTableSql.quote("update_time")).append(" = ?, ")
                .append(DynamicTableSql.quote("update_by")).append(" = ?")
                .append(" WHERE ").append(DynamicTableSql.quote("id")).append(" = ?")
                .append(" AND ").append(DynamicTableSql.quote("deleted")).append(" = 0")
                .append(" AND ").append(DynamicTableSql.quote("tenant_id")).append(" = ?")
                .append(" AND ").append(DynamicTableSql.quote(cols.reservedCol())).append(" >= ?");
        params.add(qty);
        params.add(now);
        params.add(userIdOrSystem(userId));
        params.add(reservation.getRecordId());
        params.add(tenantId);
        params.add(qty);
        if (confirm) {
            sql.append(" AND ").append(DynamicTableSql.quote(cols.balanceCol())).append(" >= ?");
            params.add(qty);
        }
        int updated = DynamicTableSql.update(jdbcTemplate, cols.table(), sql.toString(), params.toArray());
        if (updated != 1) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_TARGET_NOT_FOUND,
                    "预占目标记录状态异常，结算被拒绝");
        }
        RowSnapshot after = readOnly(cols, reservation.getRecordId(), tenantId);
        insertLedger(action, version, invocationId, reservation.getId(), confirm ? "CONFIRM" : "RELEASE",
                form.getId(), reservation.getRecordId(), qty, after, null, userId);
        insertInvocation(action, version, invocationId, invocationKey, requestHash, reservation.getRecordId(),
                "SUCCEEDED", null, null, successResultJson(reservation.getId(), qty, after),
                System.currentTimeMillis() - start, userId);
        return new TxnInvokeResult(invocationId, "SUCCEEDED", version.getVersionNo(), reservation.getId(),
                qty, after.balance(), after.reserved(), null, null,
                System.currentTimeMillis() - start, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public TxnInvokeResult adjust(TxnActionEntity action, TxnActionVersionEntity version, TxnActionConfig cfg,
                                  TxnInvokeRequest req, String invocationId, String invocationKey,
                                  String requestHash) {
        long start = System.currentTimeMillis();
        FormDefEntity form = binding.requirePublishedForm(action.getFormId());
        Long tenantId = currentTenantId();
        Long userId = currentUserId();
        Columns cols = resolveColumns(form, cfg);
        BigDecimal delta = requireSignedQuantity(req.getQuantity(), cfg);

        RowSnapshot before = lockAndRead(action.getFormId(), form, cols, req.getRecordId(), tenantId);
        checkVersion(req, before);
        verifyBusinessKeys(req, before);

        boolean nonNegative = cfg.getNonNegativeAvailable() == null || Boolean.TRUE.equals(cfg.getNonNegativeAvailable());
        String reservedExpression = cols.reservedCol() == null
                ? "0" : DynamicTableSql.quote(cols.reservedCol());
        StringBuilder sql = new StringBuilder("UPDATE ").append(DynamicTableSql.quote(cols.table()))
                .append(" SET ").append(DynamicTableSql.quote(cols.balanceCol())).append(" = ")
                .append(DynamicTableSql.quote(cols.balanceCol())).append(" + ?, ")
                .append(DynamicTableSql.quote("version")).append(" = ").append(DynamicTableSql.quote("version")).append(" + 1, ")
                .append(DynamicTableSql.quote("update_time")).append(" = ?, ")
                .append(DynamicTableSql.quote("update_by")).append(" = ?")
                .append(" WHERE ").append(DynamicTableSql.quote("id")).append(" = ?")
                .append(" AND ").append(DynamicTableSql.quote("deleted")).append(" = 0")
                .append(" AND ").append(DynamicTableSql.quote("tenant_id")).append(" = ?");
        List<Object> params = new ArrayList<>(List.of(delta, LocalDateTime.now(), userIdOrSystem(userId),
                req.getRecordId(), tenantId));
        if (nonNegative) {
            sql.append(" AND (").append(DynamicTableSql.quote(cols.balanceCol())).append(" + ? - ")
                    .append(reservedExpression).append(") >= 0");
            params.add(delta);
        }
        int updated = DynamicTableSql.update(jdbcTemplate, cols.table(), sql.toString(), params.toArray());
        if (updated != 1) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_INSUFFICIENT_AVAILABLE,
                    "调整后可用量将为负，操作被拒绝");
        }
        RowSnapshot after = readOnly(cols, req.getRecordId(), tenantId);
        insertLedger(action, version, invocationId, null, "ADJUST", form.getId(),
                req.getRecordId(), delta, after, before.keyValues(), userId);
        insertInvocation(action, version, invocationId, invocationKey, requestHash, req.getRecordId(),
                "SUCCEEDED", null, null, successResultJson(null, delta, after),
                System.currentTimeMillis() - start, userId);
        return new TxnInvokeResult(invocationId, "SUCCEEDED", version.getVersionNo(), null,
                delta, after.balance(), after.reserved(), null, null,
                System.currentTimeMillis() - start, false);
    }

    // ==================== 过期释放（调度线程，逐行还原租户后调用） ====================

    @Transactional(rollbackFor = Exception.class)
    public boolean settleExpired(TxnReservationEntity reservation, String invocationId, String invocationKey,
                                 String requestHash, Long callerId) {
        Long tenantId = reservation.getTenantId() == null ? 0L : reservation.getTenantId();
        LocalDateTime now = LocalDateTime.now();
        int claimed = reservationMapper.update(null, Wrappers.<TxnReservationEntity>lambdaUpdate()
                .eq(TxnReservationEntity::getId, reservation.getId())
                .eq(TxnReservationEntity::getStatus, STATUS_ACTIVE)
                .le(TxnReservationEntity::getExpiresAt, now)
                .set(TxnReservationEntity::getStatus, STATUS_EXPIRED)
                .set(TxnReservationEntity::getSettleInvocationId, invocationId)
                .set(TxnReservationEntity::getSettledAt, now)
                .set(TxnReservationEntity::getUpdateTime, now));
        if (claimed == 0) {
            return false;
        }
        TxnActionEntity action = new TxnActionEntity();
        action.setId(reservation.getActionId());
        action.setFormId(reservation.getFormId());
        FormDefEntity form = binding.requirePublishedForm(reservation.getFormId());
        TxnActionVersionEntity version = new TxnActionVersionEntity();
        version.setVersionNo(reservation.getActionVersion());
        // 过期释放沿用预占受理时的字段绑定（从预占动作版本配置读取）
        TxnActionConfig cfg = versionConfig(reservation);
        Columns cols = resolveColumns(form, cfg);
        BigDecimal qty = reservation.getQuantity();
        int updated;
        if (cols.reservedCol() == null) {
            updated = 1;
        } else {
            String sql = "UPDATE " + DynamicTableSql.quote(cols.table())
                    + " SET " + DynamicTableSql.quote(cols.reservedCol()) + " = "
                    + DynamicTableSql.quote(cols.reservedCol()) + " - ?, "
                    + DynamicTableSql.quote("version") + " = " + DynamicTableSql.quote("version") + " + 1, "
                    + DynamicTableSql.quote("update_time") + " = ?, "
                    + DynamicTableSql.quote("update_by") + " = ?"
                    + " WHERE " + DynamicTableSql.quote("id") + " = ?"
                    + " AND " + DynamicTableSql.quote("deleted") + " = 0"
                    + " AND " + DynamicTableSql.quote("tenant_id") + " = ?"
                    + " AND " + DynamicTableSql.quote(cols.reservedCol()) + " >= ?";
            updated = DynamicTableSql.update(jdbcTemplate, cols.table(), sql,
                    qty, now, callerId, reservation.getRecordId(), tenantId, qty);
        }
        if (updated != 1) {
            // 目标行不可用（已删/状态异常）：保留 EXPIRED 终态并记录失败日志，不再产生台账（不伪造效果）
            insertInvocation(action, version, invocationId, invocationKey, requestHash, reservation.getRecordId(),
                    "FAILED", FormErrorCode.ACTION_TARGET_NOT_FOUND.getCode(),
                    "过期释放时目标记录不可用", null, 0L, callerId);
            return false;
        }
        RowSnapshot after = readOnly(cols, reservation.getRecordId(), tenantId);
        insertLedger(action, version, invocationId, reservation.getId(), "EXPIRE", form.getId(),
                reservation.getRecordId(), qty, after, null, callerId);
        insertInvocation(action, version, invocationId, invocationKey, requestHash, reservation.getRecordId(),
                "SUCCEEDED", null, null, successResultJson(reservation.getId(), qty, after), 0L, callerId);
        return true;
    }

    /** 读取预占受理动作版本的配置（版本固定语义）。 */
    public TxnActionConfig versionConfig(TxnReservationEntity reservation) {
        TxnActionVersionEntity version = versionMapper.selectOne(
                Wrappers.<TxnActionVersionEntity>lambdaQuery()
                        .eq(TxnActionVersionEntity::getActionId, reservation.getActionId())
                        .eq(TxnActionVersionEntity::getVersionNo, reservation.getActionVersion()));
        if (version == null) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_NOT_FOUND,
                    "预占对应的动作版本快照缺失，无法释放");
        }
        try {
            return objectMapper.readValue(version.getConfigJson(), TxnActionConfig.class);
        } catch (Exception e) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_CONFIG_INVALID, "动作版本配置解析失败");
        }
    }

    // ==================== 调用记录（拒绝路径，独立事务） ====================

    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public TxnInvokeResult recordRejected(TxnActionEntity action, TxnActionVersionEntity version,
                                          TxnInvokeRequest req, String invocationId, String invocationKey,
                                          String requestHash, FormErrorCode errorCode, String message,
                                          long durationMs) {
        Long userId = currentUserId();
        insertInvocation(action, version, invocationId, invocationKey, requestHash, req.getRecordId(),
                "REJECTED", errorCode.getCode(), message, null, durationMs, userId);
        return new TxnInvokeResult(invocationId, "REJECTED", version.getVersionNo(),
                req.getReservationId(), null, null, null, errorCode.getCode(), message, durationMs, false);
    }

    // ==================== 内部工具 ====================

    private RowSnapshot readOnly(Columns cols, String recordId, Long tenantId) {
        StringBuilder select = new StringBuilder("SELECT ")
                .append(DynamicTableSql.quote(cols.balanceCol()));
        if (cols.reservedCol() != null) {
            select.append(", ").append(DynamicTableSql.quote(cols.reservedCol()));
        }
        select.append(" FROM ").append(DynamicTableSql.quote(cols.table()))
                .append(" WHERE ").append(DynamicTableSql.quote("id")).append(" = ?")
                .append(" AND ").append(DynamicTableSql.quote("deleted")).append(" = 0")
                .append(" AND ").append(DynamicTableSql.quote("tenant_id")).append(" = ?");
        List<Map<String, Object>> rows = DynamicTableSql.query(jdbcTemplate, cols.table(),
                select.toString(), recordId, tenantId);
        if (rows.isEmpty()) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_TARGET_NOT_FOUND, "动作目标记录不存在或已删除");
        }
        Map<String, Object> row = rows.get(0);
        BigDecimal balance = toDecimal(getVal(row, cols.balanceCol()));
        BigDecimal reserved = cols.reservedCol() == null
                ? BigDecimal.ZERO : toDecimal(getVal(row, cols.reservedCol()));
        return new RowSnapshot(balance, reserved, null, Map.of());
    }

    private void checkVersion(TxnInvokeRequest req, RowSnapshot snapshot) {
        if (req.getExpectedVersion() != null && snapshot.version() != null
                && !req.getExpectedVersion().equals(snapshot.version())) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_VERSION_CONFLICT,
                    "目标数据版本冲突（期望 " + req.getExpectedVersion() + "，实际 " + snapshot.version() + "）");
        }
    }

    private void verifyBusinessKeys(TxnInvokeRequest req, RowSnapshot snapshot) {
        if (req.getBusinessKeys() == null || req.getBusinessKeys().isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : req.getBusinessKeys().entrySet()) {
            if (!snapshot.keyValues().containsKey(e.getKey())) {
                throw new TxnBusinessRejection(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                        "业务键未在动作配置中声明：" + e.getKey());
            }
            Object actual = snapshot.keyValues().get(e.getKey());
            String expected = e.getValue() == null ? null : String.valueOf(e.getValue());
            if (!Objects.equals(actual == null ? null : String.valueOf(actual), expected)) {
                throw new TxnBusinessRejection(FormErrorCode.ACTION_FIELD_BINDING_INVALID,
                        "业务键与目标记录不一致：" + e.getKey());
            }
        }
    }

    private BigDecimal requirePositiveQuantity(String raw, TxnActionConfig cfg) {
        BigDecimal qty = parseQuantity(raw, cfg);
        if (qty.signum() <= 0) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_QUANTITY_INVALID, "数量必须大于 0");
        }
        return qty;
    }

    private BigDecimal requireSignedQuantity(String raw, TxnActionConfig cfg) {
        BigDecimal qty = parseQuantity(raw, cfg);
        if (qty.signum() == 0) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_QUANTITY_INVALID, "调整数量不能为 0");
        }
        return qty;
    }

    private BigDecimal parseQuantity(String raw, TxnActionConfig cfg) {
        BigDecimal qty;
        try {
            qty = new BigDecimal(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_QUANTITY_INVALID, "数量格式不合法");
        }
        int scale = cfg.getQuantityScale() == null ? 3 : cfg.getQuantityScale();
        if (qty.stripTrailingZeros().scale() > scale) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_QUANTITY_INVALID,
                    "数量超过模型声明精度（" + scale + " 位小数）");
        }
        if (qty.abs().compareTo(MAX_QUANTITY) >= 0) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_QUANTITY_INVALID, "数量超出可受理范围");
        }
        return qty;
    }

    private void insertLedger(TxnActionEntity action, TxnActionVersionEntity version, String invocationId,
                              String reservationId, String entryType, String formId, String recordId,
                              BigDecimal quantity, RowSnapshot after, Map<String, Object> keys, Long userId) {
        TxnLedgerEntity ledger = new TxnLedgerEntity();
        ledger.setId(idGenerator.generate());
        ledger.setActionId(action.getId());
        ledger.setActionVersion(version.getVersionNo());
        ledger.setInvocationId(invocationId);
        ledger.setReservationId(reservationId);
        ledger.setEntryType(entryType);
        ledger.setFormId(formId);
        ledger.setRecordId(recordId);
        ledger.setQuantity(quantity);
        ledger.setBalanceAfter(after.balance());
        ledger.setReservedAfter(after.reserved());
        if (keys != null && !keys.isEmpty()) {
            ledger.setBizKeysJson(writeJson(keys));
        }
        if (userId != null) {
            ledger.setCreateBy(userId);
            ledger.setUpdateBy(userId);
        }
        ledgerMapper.insert(ledger);
    }

    private void insertInvocation(TxnActionEntity action, TxnActionVersionEntity version, String invocationId,
                                  String invocationKey, String requestHash, String recordId, String status,
                                  Integer errorCode, String errorMsg, String resultJson, long durationMs,
                                  Long userId) {
        TxnInvocationEntity invocation = new TxnInvocationEntity();
        invocation.setId(invocationId);
        invocation.setActionId(action.getId());
        invocation.setActionVersion(version.getVersionNo());
        invocation.setInvocationKey(invocationKey);
        invocation.setRequestHash(requestHash);
        invocation.setBizRecordId(recordId);
        invocation.setStatus(status);
        invocation.setErrorCode(errorCode);
        invocation.setErrorMsg(errorMsg);
        invocation.setResultJson(resultJson);
        invocation.setDurationMs(durationMs);
        if (userId != null) {
            invocation.setCreateBy(userId);
            invocation.setUpdateBy(userId);
        }
        invocationMapper.insert(invocation);
    }

    private String successResultJson(String reservationId, BigDecimal quantity, RowSnapshot after) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservationId", reservationId);
        result.put("quantity", quantity);
        result.put("balanceAfter", after.balance());
        result.put("reservedAfter", after.reserved());
        return writeJson(result);
    }

    private Object getVal(Map<String, Object> row, String col) {
        if (row.containsKey(col)) {
            return row.get(col);
        }
        if (row.containsKey(col.toLowerCase())) {
            return row.get(col.toLowerCase());
        }
        if (row.containsKey(col.toUpperCase())) {
            return row.get(col.toUpperCase());
        }
        return null;
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private Long currentTenantId() {
        com.sw.ck.security.holder.LoginUser user = com.sw.ck.security.holder.LoginUserHolder.get();
        return user == null ? 0L : user.getTenantId();
    }

    private Long currentUserId() {
        com.sw.ck.security.holder.LoginUser user = com.sw.ck.security.holder.LoginUserHolder.get();
        return user == null ? null : user.getUserId();
    }

    private Long userIdOrSystem(Long userId) {
        return userId == null ? com.sw.ck.common.constant.CommonConstants.SYSTEM_OPERATOR_ID : userId;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new TxnBusinessRejection(FormErrorCode.ACTION_CONFIG_INVALID, "结果序列化失败");
        }
    }
}
