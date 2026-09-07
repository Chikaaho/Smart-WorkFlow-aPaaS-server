package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 表单历史版本快照详情 DTO。
 * <p>
 * 只读预览用：返回指定版本的完整 definition JSON，供前端历史版本预览，
 * 不提供任何回写路径（历史内容不得覆盖当前草稿）。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormSnapshotDetailDTO implements Serializable {

    /** 快照版本号 */
    private Integer formVersion;

    /** 快照产生时间（即该版本发布时间） */
    private LocalDateTime createTime;

    /** 该版本的完整 definition JSON */
    private String definition;
}
