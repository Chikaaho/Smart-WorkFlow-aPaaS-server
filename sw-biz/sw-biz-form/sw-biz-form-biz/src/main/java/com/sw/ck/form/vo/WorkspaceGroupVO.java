package com.sw.ck.form.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import com.sw.ck.common.value.DynamicData;

/**
 * 工作台聚合分组：可选一级/二级分组键与指标值列表。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceGroupVO {

    private DynamicData key;

    private DynamicData key2;

    private List<DynamicData> values;
}
