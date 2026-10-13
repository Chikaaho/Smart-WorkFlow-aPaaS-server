package com.sw.ck.form.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建表单草稿请求。
 */
@Data
public class FormCreateReq implements Serializable {

    /** 表单业务标识（唯一，如 leave_request） */
    private String formKey;

    /** 表单名称 */
    private String name;

    /** 用户自定义逻辑表名 */
    private String logicalTableName;

    /** 表单描述 */
    private String description;

    /** 表单类型：BUSINESS 业务表单（缺省）/ APPROVAL_COMMENT 审批意见表单（BF20261013-020）。 */
    private String formType;
}
