package com.sw.ck.form.txn.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
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
import com.sw.ck.form.txn.model.TxnInvocationView;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.model.TxnLedgerView;
import com.sw.ck.form.txn.model.TxnReservationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 事务动作执行器（编排层，无事务）：
 * 幂等判重（同键同指纹重放 / 同键不同指纹冲突）→ 事务内核执行 → 业务拒绝独立事务记录。
 */
@Service
public class TxnActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(TxnActionExecutor.class);

    private final TxnActionService actionService;
    private final TxnActionTxOperations txOps;
    private final TxnInvocationMapper invocationMapper;
    private final TxnReservationMapper reservationMapper;
    private final TxnLedgerMapper ledgerMapper;
    private final FormIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public TxnActionExecutor(TxnActionService actionService,
                             TxnActionTxOperations txOps,
                             TxnInvocationMapper invocationMapper,
                             TxnReservationMapper reservationMapper,
                             TxnLedgerMapper ledgerMapper,
                             FormIdGenerator idGenerator,
                             ObjectMapper objectMapper) {
        this.actionService = actionService;
        this.txOps = txOps;
        this.invocationMapper = invocationMapper;
        this.reservationMapper = reservationMapper;
        this.ledgerMapper = ledgerMapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    // ==================== 调用入口 ====================

    public TxnInvokeResult invoke(String actionId, TxnInvokeRequest req) {
        if (req == null) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "请求体缺失");
        }
        TxnActionEntity action = actionService.requireAction(actionId);
        String requestKey = req.getInvocationKey() == null || req.getInvocationKey().isBlank()
                ? null : req.getInvocationKey().trim();
        String requestHash = hashRequest(action, req);
        // 幂等优先：已受理请求的回查（重放）不受动作停用影响
        if (requestKey != null) {
            TxnInvocationEntity existing = findInvocation(action.getId(), requestKey);
            if (existing != null) {
                return replayOrConflict(existing, requestHash);
            }
        }
        String invocationId = idGenerator.generate();
        String storedKey = requestKey != null ? requestKey : "AUTO:" + invocationId;
        long start = System.currentTimeMillis();
        String type = action.getActionType() == null ? "" : action.getActionType().toUpperCase();
        // 结算类调用（CONFIRM/RELEASE + 预占凭据）沿用预占受理时的冻结版本语义：
        // 停用只拒绝新调用，既有凭据仍可结算（版本由结算方法从凭据解析）
        boolean settleByReservation = (TxnActionService.TYPE_CONFIRM.equals(type)
                || TxnActionService.TYPE_RELEASE.equals(type))
                && req.getReservationId() != null && !req.getReservationId().isBlank();
        TxnActionVersionEntity version = settleByReservation ? null : actionService.requireCurrentVersion(action);
        TxnActionConfig cfg = version == null ? null : actionService.readConfig(version.getConfigJson());
        if (!settleByReservation && cfg == null) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "动作版本配置为空，请重新发布");
        }
        try {
            return switch (type) {
                case TxnActionService.TYPE_RESERVE ->
                        txOps.reserve(action, version, cfg, req, invocationId, storedKey, requestHash);
                case TxnActionService.TYPE_CONFIRM ->
                        txOps.confirm(action, req, invocationId, storedKey, requestHash);
                case TxnActionService.TYPE_RELEASE ->
                        txOps.release(action, req, invocationId, storedKey, requestHash);
                case TxnActionService.TYPE_ADJUST ->
                        txOps.adjust(action, version, cfg, req, invocationId, storedKey, requestHash);
                default -> throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "动作类型不合法：" + type);
            };
        } catch (TxnBusinessRejection rejection) {
            long duration = System.currentTimeMillis() - start;
            // 拒绝记录需要一个已发布版本号；结算类调用可能未解析当前版本，此处回退取当前版本
            TxnActionVersionEntity recordVersion = version != null ? version
                    : actionService.currentVersionOrNull(action);
            if (recordVersion == null) {
                throw new BaseException(rejection.getErrorCode(), rejection.getMessage());
            }
            try {
                return txOps.recordRejected(action, recordVersion, req, invocationId, storedKey, requestHash,
                        rejection.getErrorCode(), rejection.getMessage(), duration);
            } catch (DuplicateKeyException race) {
                TxnInvocationEntity existing = findInvocation(action.getId(), storedKey);
                if (existing != null) {
                    return replayOrConflict(existing, requestHash);
                }
                throw race;
            }
        } catch (DuplicateKeyException duplicate) {
            if (requestKey != null) {
                TxnInvocationEntity existing = findInvocation(action.getId(), requestKey);
                if (existing != null) {
                    return replayOrConflict(existing, requestHash);
                }
            }
            throw new BaseException(FormErrorCode.ACTION_IDEMPOTENCY_CONFLICT,
                    "并发重复调用被拒绝，请以原调用标识回查结果");
        }
    }

    // ==================== 结果与记录查询 ====================

    public TxnInvocationView getInvocation(String invocationId) {
        TxnInvocationEntity entity = invocationId == null ? null : invocationMapper.selectById(invocationId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.ACTION_NOT_FOUND, "调用记录不存在");
        }
        return toInvocationView(entity);
    }

    public Page<TxnInvocationView> pageInvocations(String actionId, String status, Integer actionVersion,
                                                   Long callerId, long page, long size) {
        Page<TxnInvocationEntity> p = invocationMapper.selectPage(new Page<>(page, capSize(size)),
                Wrappers.<TxnInvocationEntity>lambdaQuery()
                        .eq(TxnInvocationEntity::getActionId, actionId)
                        .eq(status != null && !status.isBlank(), TxnInvocationEntity::getStatus, status)
                        .eq(actionVersion != null, TxnInvocationEntity::getActionVersion, actionVersion)
                        .eq(callerId != null, TxnInvocationEntity::getCreateBy, callerId)
                        .orderByDesc(TxnInvocationEntity::getCreateTime));
        Page<TxnInvocationView> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<TxnInvocationView> views = new ArrayList<>();
        for (TxnInvocationEntity e : p.getRecords()) {
            views.add(toInvocationView(e));
        }
        result.setRecords(views);
        return result;
    }

    public TxnReservationView getReservation(String reservationId) {
        TxnReservationEntity entity = reservationId == null ? null : reservationMapper.selectById(reservationId);
        if (entity == null) {
            throw new BaseException(FormErrorCode.ACTION_RESERVATION_NOT_FOUND, "预占凭据不存在");
        }
        return toReservationView(entity);
    }

    public Page<TxnReservationView> pageReservations(String actionId, String status, long page, long size) {
        Page<TxnReservationEntity> p = reservationMapper.selectPage(new Page<>(page, capSize(size)),
                Wrappers.<TxnReservationEntity>lambdaQuery()
                        .eq(TxnReservationEntity::getActionId, actionId)
                        .eq(status != null && !status.isBlank(), TxnReservationEntity::getStatus, status)
                        .orderByDesc(TxnReservationEntity::getCreateTime));
        Page<TxnReservationView> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<TxnReservationView> views = new ArrayList<>();
        for (TxnReservationEntity e : p.getRecords()) {
            views.add(toReservationView(e));
        }
        result.setRecords(views);
        return result;
    }

    public Page<TxnLedgerView> pageLedger(String actionId, Integer actionVersion, String reservationId,
                                          long page, long size) {
        Page<TxnLedgerEntity> p = ledgerMapper.selectPage(new Page<>(page, capSize(size)),
                Wrappers.<TxnLedgerEntity>lambdaQuery()
                        .eq(TxnLedgerEntity::getActionId, actionId)
                        .eq(actionVersion != null, TxnLedgerEntity::getActionVersion, actionVersion)
                        .eq(reservationId != null && !reservationId.isBlank(),
                                TxnLedgerEntity::getReservationId, reservationId)
                        .orderByDesc(TxnLedgerEntity::getCreateTime));
        Page<TxnLedgerView> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<TxnLedgerView> views = new ArrayList<>();
        for (TxnLedgerEntity e : p.getRecords()) {
            views.add(new TxnLedgerView(e.getId(), e.getActionId(), e.getActionVersion(), e.getInvocationId(),
                    e.getReservationId(), e.getEntryType(), e.getFormId(), e.getRecordId(),
                    e.getQuantity(), e.getBalanceAfter(), e.getReservedAfter(), e.getBizKeysJson(),
                    e.getCreateTime()));
        }
        result.setRecords(views);
        return result;
    }

    // ==================== 内部 ====================

    private TxnInvocationEntity findInvocation(String actionId, String invocationKey) {
        return invocationMapper.selectOne(Wrappers.<TxnInvocationEntity>lambdaQuery()
                .eq(TxnInvocationEntity::getActionId, actionId)
                .eq(TxnInvocationEntity::getInvocationKey, invocationKey));
    }

    /** 同键：同指纹重放原结果；不同指纹明确冲突。 */
    private TxnInvokeResult replayOrConflict(TxnInvocationEntity existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new BaseException(FormErrorCode.ACTION_IDEMPOTENCY_CONFLICT,
                    "相同调用标识已存在且请求内容不同，已拒绝（原结果可凭该标识回查）");
        }
        String reservationId = null;
        java.math.BigDecimal quantity = null;
        java.math.BigDecimal balanceAfter = null;
        java.math.BigDecimal reservedAfter = null;
        if (existing.getResultJson() != null && !existing.getResultJson().isBlank()) {
            try {
                Map<?, ?> map = objectMapper.readValue(existing.getResultJson(), Map.class);
                Object rid = map.get("reservationId");
                reservationId = rid == null ? null : String.valueOf(rid);
                quantity = decimalOrNull(map.get("quantity"));
                balanceAfter = decimalOrNull(map.get("balanceAfter"));
                reservedAfter = decimalOrNull(map.get("reservedAfter"));
            } catch (Exception e) {
                log.warn("调用结果解析失败 invocationId={}: {}", existing.getId(), e.getMessage());
            }
        }
        return new TxnInvokeResult(existing.getId(), existing.getStatus(), existing.getActionVersion(),
                reservationId, quantity, balanceAfter, reservedAfter,
                existing.getErrorCode(), existing.getErrorMsg(), existing.getDurationMs(), true);
    }

    private java.math.BigDecimal decimalOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String hashRequest(TxnActionEntity action, TxnInvokeRequest req) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("actionId", action.getId());
        canonical.put("actionType", action.getActionType());
        canonical.put("recordId", req.getRecordId());
        canonical.put("reservationId", req.getReservationId());
        canonical.put("quantity", req.getQuantity());
        canonical.put("expectedVersion", req.getExpectedVersion());
        if (req.getBusinessKeys() != null && !req.getBusinessKeys().isEmpty()) {
            canonical.put("businessKeys", new TreeMap<>(req.getBusinessKeys()));
        }
        try {
            String json = objectMapper.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BaseException(FormErrorCode.ACTION_CONFIG_INVALID, "请求指纹计算失败");
        }
    }

    private long capSize(long size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 200);
    }

    private TxnInvocationView toInvocationView(TxnInvocationEntity e) {
        return new TxnInvocationView(e.getId(), e.getActionId(), e.getActionVersion(), e.getInvocationKey(),
                e.getBizRecordId(), e.getStatus(), e.getErrorCode(), e.getErrorMsg(), e.getResultJson(),
                e.getDurationMs(), e.getCreateBy(), e.getCreateTime());
    }

    private TxnReservationView toReservationView(TxnReservationEntity e) {
        return new TxnReservationView(e.getId(), e.getActionId(), e.getActionVersion(), e.getFormId(),
                e.getRecordId(), e.getBizKeysJson(), e.getQuantity(), e.getStatus(), e.getExpiresAt(),
                e.getReserveInvocationId(), e.getSettleInvocationId(), e.getSettledAt(), e.getCreateTime());
    }
}
