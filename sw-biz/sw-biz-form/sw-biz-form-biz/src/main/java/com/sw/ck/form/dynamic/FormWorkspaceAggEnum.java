package com.sw.ck.form.dynamic;

import lombok.Getter;

/**
 * 工作区统计聚合方式契约（查询 DSL 的 agg 字段取值）。
 */
@Getter
public enum FormWorkspaceAggEnum {

    COUNT("COUNT", "计数"),
    SUM("SUM", "求和"),
    AVG("AVG", "平均"),
    MIN("MIN", "最小"),
    MAX("MAX", "最大"),
    ;

    private final String code;
    private final String label;

    FormWorkspaceAggEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
