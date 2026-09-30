package com.sw.ck.form.txn.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 事务台账视图。 */
public record TxnLedgerView(String id, String actionId, Integer actionVersion, String invocationId,
                            String reservationId, String entryType, String formId, String recordId,
                            BigDecimal quantity, BigDecimal balanceAfter, BigDecimal reservedAfter,
                            String bizKeysJson, LocalDateTime createTime) {
}
