package com.sw.ck.form.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 工作台聚合分组：可选一级/二级分组键与指标值列表。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceGroupDTO {

    private Object key;

    private Object key2;

    private List<Object> values;
}
