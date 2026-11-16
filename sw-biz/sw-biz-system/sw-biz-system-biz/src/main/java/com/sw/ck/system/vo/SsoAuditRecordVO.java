package com.sw.ck.system.vo;

import lombok.Data;

import com.sw.ck.system.entity.SsoAuditRecord;

/**
 * SSO 审计记录（外部标识只回摘要前缀）。
 */
@Data
public class SsoAuditRecordVO {

    private String id;

    private String provider;

    private String eventType;

    private String result;

    private String actorId;

    private String localUserId;

    private String externalDigestPrefix;

    private String detail;

    private String createTime;
}
