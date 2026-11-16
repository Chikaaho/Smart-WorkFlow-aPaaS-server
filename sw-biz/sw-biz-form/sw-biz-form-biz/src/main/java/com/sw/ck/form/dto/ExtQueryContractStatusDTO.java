package com.sw.ck.form.dto;

import lombok.Data;

/**
 * 外部查询契约启停结果：查询键、版本与启用标记（0/1）。
 */
@Data
public class ExtQueryContractStatusDTO {

    private String queryKey;

    private Integer version;

    private Integer enabled;
}
