package com.sw.ck.form.txn.model;

import java.math.BigDecimal;

/**
 * 动作调用结果（同键重放时 replay=true 且返回原结果）。
 */
public record TxnInvokeResult(String invocationId, String status, Integer actionVersion,
                              String reservationId, BigDecimal quantity,
                              BigDecimal balanceAfter, BigDecimal reservedAfter,
                              Integer errorCode, String errorMsg, Long durationMs, boolean replay) {
}
