package com.sw.ck.form.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 工作台字段目录项：字段名、中文标签、类型与可选枚举项。
 */
@Data
public class WorkspaceFieldDTO {

    private String name;

    private String label;

    private String type;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<WorkspaceOptionDTO> options;
}
