package com.sw.ck.form.dto;

import lombok.Data;

/**
 * 外部查询契约登记结果：查询键与版本。
 */
@Data
public class ExtQueryContractDTO {

    private String queryKey;

    private Integer version;
}
