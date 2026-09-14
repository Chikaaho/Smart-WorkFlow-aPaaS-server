package com.sw.ck.notify.dto;

import lombok.Data;

/** 通知规则查询（I6）。 */
@Data
public class NotifyRuleQuery {
    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String eventType;
    private Boolean enabled;
    private String keyword;
}
