package com.sw.ck.form.dto;

import lombok.Data;

import java.util.List;

/**
 * 工作台聚合结果：表单标识、版本、分组数据与记录总数。
 */
@Data
public class WorkspaceAggregateDTO {

    private String formKey;

    private Integer formVersion;

    private List<WorkspaceGroupDTO> groups;

    private long totalRecords;
}
