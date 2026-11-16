package com.sw.ck.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 租户 SSO 配置列表：含未登记平台占位。
 */
@Data
public class SsoConfigListDTO {

    private List<SsoConfigItemDTO> configs;
}
