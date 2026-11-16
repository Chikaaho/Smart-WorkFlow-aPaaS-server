package com.sw.ck.system.sso;

import lombok.Getter;

/**
 * SSO 审计结果码（SsoAuditRecord.result 落库契约）。
 */
@Getter
public enum SsoAuditResultEnum {
        DENIED("DENIED", "已拒绝"),
        FAILED("FAILED", "失败"),
        ENTERPRISE_MISMATCH("ENTERPRISE_MISMATCH", "企业不匹配"),
    ;

    private final String code;
    private final String label;

    SsoAuditResultEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
