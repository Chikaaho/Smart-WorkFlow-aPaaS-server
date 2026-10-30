package com.sw.ck.form.dto.workspace;

import lombok.Getter;
import lombok.Setter;

/** 工作台聚合指标：字段 + 统计方式（COUNT/SUM/AVG/MIN/MAX）。 */
@Getter
@Setter
public class WorkspaceMetric {

    /** 逻辑字段名；COUNT 允许为空，表示统计全部记录。 */
    private String field;

    /** 统计方式；除 COUNT 外必须为数值字段。 */
    private String agg;
}
