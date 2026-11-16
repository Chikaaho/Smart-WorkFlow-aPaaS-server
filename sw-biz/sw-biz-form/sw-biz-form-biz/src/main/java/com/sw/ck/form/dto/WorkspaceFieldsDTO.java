package com.sw.ck.form.dto;

import lombok.Data;

import java.util.List;

/**
 * 工作台表单字段目录：表单标识、版本与可见标量字段。
 */
@Data
public class WorkspaceFieldsDTO {

    private String formKey;

    private Integer formVersion;

    private List<WorkspaceFieldDTO> fields;
}
