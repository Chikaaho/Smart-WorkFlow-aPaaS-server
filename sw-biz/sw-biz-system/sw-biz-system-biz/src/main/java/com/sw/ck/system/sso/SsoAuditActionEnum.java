package com.sw.ck.system.sso;

import lombok.Getter;

/**
 * SSO 审计动作码（SsoAuditRecord.action 落库契约）。
 */
@Getter
public enum SsoAuditActionEnum {
        LOGIN_FAILED("LOGIN_FAILED", "登录失败"),
        ADMISSION_REJECTED("ADMISSION_REJECTED", "准入拒绝"),
        CONFLICT_REJECTED("CONFLICT_REJECTED", "绑定冲突拒绝"),
        REPLAY_REJECTED("REPLAY_REJECTED", "重放拒绝"),
        CONFIG_ENABLE("CONFIG_ENABLE", "启用配置"),
        CONFIG_DISABLE("CONFIG_DISABLE", "停用配置"),
        CONFIG_CHECK("CONFIG_CHECK", "配置检查"),
        CONFIG_SECRET_UPDATE("CONFIG_SECRET_UPDATE", "更新配置密钥"),
        CONFIG_REJECTED("CONFIG_REJECTED", "配置变更拒绝"),
        ENABLE("ENABLE", "启用"),
        DISABLE("DISABLE", "停用"),
    ;

    private final String code;
    private final String label;

    SsoAuditActionEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
