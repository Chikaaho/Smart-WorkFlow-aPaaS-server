package com.sw.ck.bpm.process.dto;

import lombok.Data;

/** 事项分类归属调整请求（categoryId=null 表示移入未分类）。 */
@Data
public class CategoryAssignReq {

    private Long categoryId;
}
