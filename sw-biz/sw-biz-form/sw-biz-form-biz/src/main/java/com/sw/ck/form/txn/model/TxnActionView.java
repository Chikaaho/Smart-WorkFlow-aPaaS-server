package com.sw.ck.form.txn.model;

import java.time.LocalDateTime;

/**
 * 动作管理列表视图（最小暴露：不回 tenant/deleted/version）。
 */
public record TxnActionView(String id, String formId, String actionKey, String name, String actionType,
                            String status, Integer currentVersion, String description,
                            String configJson, LocalDateTime updateTime) {
}
