package com.sw.ck.job.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务定义 DTO。
 * <p>
 * 定义于 {@code -api} 模块，供 {@link com.sw.ck.job.facade.JobFacade} 返回给外部模块。
 * 不暴露内部实体细节（deleted / tenant_id / version 等系统列）。
 * </p>
 */
@Data
public class JobInfoVO {

    /** 任务 ID */
    private Long id;

    /** 任务名称 */
    private String jobName;

    /** 任务组 */
    private String jobGroup;

    /** 任务类型（BEAN / FLOW） */
    private String jobType;

    /** Cron 表达式 */
    private String cronExpression;

    /** 任务状态（NORMAL / PAUSED） */
    private String status;

    /** 是否允许并发 */
    private Boolean concurrent;

    /** Misfire 策略 */
    private Integer misfirePolicy;

    /** 任务描述 */
    private String description;

    /** Bean 名称（BEAN 类型） */
    private String beanName;

    /** 流程定义 Key（FLOW 类型） */
    private String flowDefKey;

    /** 上次执行时间 */
    private LocalDateTime lastFireTime;

    /** 下次执行时间 */
    private LocalDateTime nextFireTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
