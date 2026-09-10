package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 受控外部数据源查询结果（form 侧统一形状）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtQueryResult implements Serializable {

    /** 列名列表 */
    private List<String> columns;

    /** 数据行（列名 → 值） */
    private List<Map<String, Object>> rows;

    /** 返回行数 */
    private int rowCount;
}
