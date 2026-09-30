package com.sw.ck.form.txn.model;

import java.time.LocalDateTime;

/** C1 策略视图。 */
public record C1PolicyView(String id, String formId, boolean enabled, String policyJson,
                           LocalDateTime appliedAt, LocalDateTime updateTime) {
}
