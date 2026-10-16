package com.sw.ck.form.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 表单仅改名请求（0.1.7 #4：不动 formKey、定义与物理列，全部状态可改）。
 */
@Data
public class FormRenameReq implements Serializable {

    /** 新表单名称（必填，≤200 字） */
    private String name;
}
