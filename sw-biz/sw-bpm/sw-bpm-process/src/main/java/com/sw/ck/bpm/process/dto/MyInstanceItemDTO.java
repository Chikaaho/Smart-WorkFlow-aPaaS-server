package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我发起的实例条目（V012-BUG-022）。
 * <p>
 * 在实例实体基础上富化流程名称（{@code processName}，流程定义被删除时为 null）
 * 与发起人展示名（{@code initiatorName}，用户不可见/已删除时为 null），
 * 供「我发起的」列表与工作台业务动态直接可读渲染，不回显裸 processKey。
 * </p>
 */
@Data
public class MyInstanceItemDTO {

    private Long id;

    private String processInstanceId;

    private String processDefKey;

    /** 流程名称（富化：经流程定义解析；定义被删除时为 null）。 */
    private String processName;

    /** 实例主题（V012-BUG-010：发起时按主题规则生成；历史实例为空）。 */
    private String theme;

    private String businessKey;

    private String formKey;

    private Long initiatorId;

    /** 发起人展示名（富化；解析失败为 null）。 */
    private String initiatorName;

    /** 实例状态：RUNNING / APPROVED / REJECTED / FAILED / WITHDRAWN / DISCARDED。 */
    private String status;

    private LocalDateTime createTime;
}
