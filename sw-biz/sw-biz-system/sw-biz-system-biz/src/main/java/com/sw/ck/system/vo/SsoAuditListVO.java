package com.sw.ck.system.vo;

import lombok.Data;


import java.util.List;

/**
 * SSO 审计查询结果：记录与当前租户标识。
 */
@Data
public class SsoAuditListVO {

    private List<SsoAuditRecordVO> records;

    private String tenantId;
}
