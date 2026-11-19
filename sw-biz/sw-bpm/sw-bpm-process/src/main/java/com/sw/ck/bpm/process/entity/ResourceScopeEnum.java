package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 资源占用/拒绝归属维度（BpmResourceUsage.scope、BpmResourceRejectLog.reject_scope
 * 落库契约）。GLOBAL 全局（scope_key=0）/ TENANT 租户（scope_key=租户ID）/
 * TOTAL 段合计桶（Σsegments）/ QUOTA_SEGMENT 配额段拒绝。
 */
@Getter
public enum ResourceScopeEnum {

    GLOBAL("GLOBAL", "全局"),
    TENANT("TENANT", "租户"),
    TOTAL("TOTAL", "段合计"),
    QUOTA_SEGMENT("QUOTA_SEGMENT", "配额段"),
    ;

    private final String code;
    private final String label;

    ResourceScopeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
