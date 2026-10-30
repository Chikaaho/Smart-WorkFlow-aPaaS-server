package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

    /** 雪花 ID 超出 JS 安全整数：序列化为字符串，前端原样透传不丢精度 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    private Integer sortNo;

    /** 归属该分类的流程定义数（含未发布；删除约束依据） */
    private Long itemCount;
}
