package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 应用内容 DTO：层级为「应用 → 目录 → 表单」，应用内直接归属的表单单独列出。
 * <p>
 * 管理视角（{@code /form/app/manage/...}）返回全部业务表单；
 * 用户端视角（{@code /form/app/center/...}）只返回已发布且当前用户可见的表单。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormAppContentDTO implements Serializable {

    /** 应用元数据（用户端视角 formCount 只含可用表单） */
    private FormAppDTO app;

    /** 目录及其表单（按 sortNo、createTime 升序） */
    private List<FormAppDirDTO> dirs;

    /** 应用内直接归属的表单（dirId 为空） */
    private List<FormAppFormItemDTO> directForms;
}
