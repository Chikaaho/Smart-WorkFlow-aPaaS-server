package com.sw.ck.bpm.process.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 流程分类 DTO。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryDTO {

    private Long id;

    private String name;

    private Integer sortNo;

    /** 归属该分类的流程定义数（含未发布；删除约束依据） */
    private Long itemCount;
}
