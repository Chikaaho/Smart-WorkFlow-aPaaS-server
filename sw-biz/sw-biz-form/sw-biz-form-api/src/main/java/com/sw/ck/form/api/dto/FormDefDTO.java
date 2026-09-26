package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 表单定义 DTO。
 * <p>
 * 用于对外暴露表单元数据，不含 definition JSON。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormDefDTO implements Serializable {

    private String id;
    private String formKey;
    private String name;
    private String logicalTableName;
    private String status;
    private String physicalTableName;
    private Integer formVersion;
    private String description;
    private String visibilityScope;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /**
     * 创建人展示名（优先 real_name，其次 username）。
     * <p>
     * 仅分页列表端点批量解析下发；其余路径（详情/发起可见列表等）为 null。
     * 解析失败或查询上下文缺失时为 null，不阻断列表查询。
     * </p>
     */
    private String createByName;
}
