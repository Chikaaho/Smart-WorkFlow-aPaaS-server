package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 应用内容中的表单条目（管理视角含全部业务表单；用户端视角只含可用表单）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormAppFormItemDTO implements Serializable {

    /** 表单 ID（sw_form_def.id） */
    private String formId;

    /** 表单业务标识 */
    private String formKey;

    /** 表单名称 */
    private String name;

    /** 表单状态：DRAFT / PUBLISHED / DISABLED */
    private String status;

    /** 归属目录 ID（null=直接归属应用） */
    private String dirId;

    /** 表单版本号 */
    private Integer formVersion;
}
