package com.sw.ck.system.dto;

import lombok.Data;

/**
 * 通知 Provider 主体映射（只回摘要，不回头部明文/密文）。
 */
@Data
public class NotifySubjectBindingDTO {

    private Long id;

    private Long userId;

    private String provider;

    private String subjectDigest;

    private String bindStatus;
}
