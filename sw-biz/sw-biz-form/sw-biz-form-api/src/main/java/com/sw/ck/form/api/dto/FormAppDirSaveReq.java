package com.sw.ck.form.api.dto;

import lombok.Data;

import java.io.Serializable;

/** 应用目录新建/更新请求。 */
@Data
public class FormAppDirSaveReq implements Serializable {

    /** 目录名称（必填，同应用内不重名） */
    private String name;

    /** 排序号（可空按 0） */
    private Integer sortNo;
}
