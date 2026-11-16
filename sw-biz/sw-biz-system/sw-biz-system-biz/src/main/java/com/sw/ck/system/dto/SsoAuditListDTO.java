package com.sw.ck.system.dto;

import lombok.Data;

import java.util.List;

/**
 * SSO 审计查询结果：记录与当前租户标识。
 */
@Data
public class SsoAuditListDTO {

    private List<SsoAuditRecordDTO> records;

    private String tenantId;
}
