package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 表单历史版本快照 DTO（列表行）。
 * <p>
 * 只含版本元数据，不含 definition JSON，避免列表体积过大。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormSnapshotDTO implements Serializable {

    /** 快照版本号（与发布时 sw_form_def.form_version 对齐） */
    private Integer formVersion;

    /** 快照产生时间（即该版本发布时间） */
    private LocalDateTime createTime;
}
