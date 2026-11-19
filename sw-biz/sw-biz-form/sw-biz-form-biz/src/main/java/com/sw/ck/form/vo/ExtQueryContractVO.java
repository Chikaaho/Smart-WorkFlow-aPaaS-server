package com.sw.ck.form.vo;

import lombok.Data;

/**
 * 外部查询契约登记结果：查询键与版本。
 */
@Data
public class ExtQueryContractVO {

    private String queryKey;

    private Integer version;
}
