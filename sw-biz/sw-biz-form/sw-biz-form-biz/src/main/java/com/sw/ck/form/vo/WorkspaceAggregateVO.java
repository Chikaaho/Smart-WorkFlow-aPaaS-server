package com.sw.ck.form.vo;

import lombok.Data;

import java.util.List;

/**
 * 工作台聚合结果：表单标识、版本、分组数据与记录总数。
 */
@Data
public class WorkspaceAggregateVO {

    private String formKey;

    private Integer formVersion;

    private List<WorkspaceGroupVO> groups;

    private long totalRecords;
}
