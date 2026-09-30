package com.sw.ck.form.txn.model;

/**
 * 动作保存请求（草稿创建/更新）。
 */
public record TxnActionSaveRequest(String actionKey, String name, String actionType,
                                   String description, TxnActionConfig config) {
}
