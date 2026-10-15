package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 应用目录 DTO（应用内二级分类，单层）。
 * <p>
 * {@code forms} 仅在应用内容查询时填充：管理视角为目录内全部业务表单，
 * 用户端视角为过滤后的可用表单（未发布/停用/不可见表单不下发）。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormAppDirDTO implements Serializable {

    /** 目录 ID */
    private String id;

    /** 所属应用 ID */
    private String appId;

    /** 目录名称 */
    private String name;

    /** 排序号（升序） */
    private Integer sortNo;

    /** 目录内表单数 */
    private Long formCount;

    /** 目录内表单（内容查询时填充，其他场景为 null） */
    private List<FormAppFormItemDTO> forms;
}
