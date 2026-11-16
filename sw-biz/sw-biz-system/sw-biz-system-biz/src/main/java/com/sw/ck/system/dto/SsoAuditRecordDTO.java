package com.sw.ck.system.dto;

import lombok.Data;

/**
 * SSO 审计记录（外部标识只回摘要前缀）。
 */
@Data
public class SsoAuditRecordDTO {

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
