package com.sw.ck.form.dto.workspace;

import com.sw.ck.form.api.dto.FormDataFilter;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 工作台聚合请求：一个维度（可加第二维度）+ 多指标 + 过滤。 */
@Getter
@Setter
public class WorkspaceAggregateReq {

    private String formKey;

    /** 第一分组维度；为空时返回单行总值（指标卡/仪表盘）。 */
    private String groupBy;

    /** 第二分组维度；仅热力图等双维度图型使用。 */
    private String groupBy2;

    private List<WorkspaceMetric> metrics;

    private List<FormDataFilter> filters;

    /** 最大分组数，服务端钳制到 1..100。 */
    private Integer limit;
}
