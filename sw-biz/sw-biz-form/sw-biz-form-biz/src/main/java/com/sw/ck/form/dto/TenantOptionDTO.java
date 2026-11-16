package com.sw.ck.form.dto;

import lombok.Data;

/**
 * 租户选择项：仅 id 与名称，供可见范围选择器展示。
 */
@Data
public class TenantOptionDTO {

    private Long id;

    private String name;
}
