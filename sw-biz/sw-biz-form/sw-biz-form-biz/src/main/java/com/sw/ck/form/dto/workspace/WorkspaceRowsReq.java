package com.sw.ck.form.dto.workspace;

import com.sw.ck.form.api.dto.FormDataFilter;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 工作台数据列表请求：显式列 + 过滤 + 排序 + 有界分页。 */
@Getter
@Setter
public class WorkspaceRowsReq {

    private String formKey;

    /** 逻辑列名；为空时取前 4 个可见标量字段。 */
    private List<String> columns;

    private List<FormDataFilter> filters;

    private String sortField;

    /** ASC / DESC，缺省 DESC。 */
    private String sortOrder;

    private Integer pageNum;

    private Integer pageSize;
}
