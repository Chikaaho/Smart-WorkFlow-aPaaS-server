package com.sw.ck.bpm.process.dto;

import lombok.Data;

/** 流程分类保存请求（创建/更新）。 */
@Data
public class CategorySaveReq {

    /** 分类名称（必填，租户内唯一） */
    private String name;

    /** 排序号（可选，默认 0） */
    private Integer sortNo;
}
