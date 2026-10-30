package com.sw.ck.form.dto;

import java.util.List;
import java.util.Map;

/**
 * 外部数据源查询契约设计视图（F020）。
 * <p>
 * 面向设计器可选项与数据源管理页的契约清单条目；SQL 文本属服务端专用敏感数据，
 * 永不进入本视图（红线：SQL 不进 definition、前端或客户端请求）。
 * </p>
 */
public record ExtQueryContractView(
        String queryKey,
        Integer queryVersion,
        Long datasourceId,
        List<Map<String, Object>> outputFields,
        Integer enabled) {
}
