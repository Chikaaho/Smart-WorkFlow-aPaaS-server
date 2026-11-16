package com.sw.ck.form.dto;

import lombok.Data;

/**
 * 字段类型能力目录项：类型标识与启用状态。
 */
@Data
public class FieldTypeOptionDTO {

    private String type;

    private boolean enabled;
}
