package com.sw.ck.form.dto;

import lombok.Data;

/**
 * 已发布表单候选：仅暴露 formKey/name/formVersion 最小集。
 */
@Data
public class PublishedFormOptionDTO {

    private String formKey;

    private String name;

    private Integer formVersion;
}
