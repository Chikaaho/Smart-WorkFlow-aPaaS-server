package com.sw.ck.form.txn.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 预占凭据视图。 */
public record TxnReservationView(String id, String actionId, Integer actionVersion, String formId,
                                 String recordId, String bizKeysJson, BigDecimal quantity, String status,
                                 LocalDateTime expiresAt, String reserveInvocationId,
                                 String settleInvocationId, LocalDateTime settledAt,
                                 LocalDateTime createTime) {
}
