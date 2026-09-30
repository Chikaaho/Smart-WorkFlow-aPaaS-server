package com.sw.ck.form.txn.model;

import java.time.LocalDateTime;

/** 动作调用记录视图。 */
public record TxnInvocationView(String id, String actionId, Integer actionVersion, String invocationKey,
                                String bizRecordId, String status, Integer errorCode, String errorMsg,
                                String resultJson, Long durationMs, Long callerId, LocalDateTime createTime) {
}
