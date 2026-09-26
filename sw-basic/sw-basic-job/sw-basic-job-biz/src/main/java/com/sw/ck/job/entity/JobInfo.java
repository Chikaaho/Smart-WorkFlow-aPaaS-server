package com.sw.ck.job.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 定时任务定义实体。
 * <p>
 * 每条记录 = 一个可调度的定时任务。{@code tenant_id / 审计列 / deleted / version}
 * 由 MyBatis-Plus 拦截器自动注入。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_job_info")
public class JobInfo extends BaseEntity {

    /** 任务名称 */
    private String jobName;

    /** 任务组（用于 Quartz 分组管理） */
    private String jobGroup;

    /** 任务类型枚举（BEAN / FLOW） */
    private String jobType;

    /** Cron 表达式 */
    private String cronExpression;

    /** 任务状态（NORMAL=启用 / PAUSED=停用） */
    private String status;

    /**
     * 是否允许并发执行（true=允许 / false=不允许）。
     * <p>
     * 列类型为 {@code SMALLINT}：PostgreSQL 不接受 boolean→smallint 隐式转换，
     * 故显式绑定类型处理器（H2 曾掩盖该差异）。
     * </p>
     */
    @com.baomidou.mybatisplus.annotation.TableField(value = "concurrent",
            typeHandler = com.sw.ck.job.typehandler.BooleanSmallintTypeHandler.class)
    private Boolean concurrent;

    /** Misfire 策略（0=忽略 / 1=立即触发一次 / 2=放弃） */
    private Integer misfirePolicy;

    /** 任务描述 */
    private String description;

    // ─── BEAN 类型参数 ───

    /** Bean 名称（job_type=BEAN 时必填） */
    private String beanName;

    /** 方法参数（JSON 字符串，可选） */
    private String beanParams;

    // ─── FLOW 类型参数 ───

    /** 流程定义 Key（job_type=FLOW 时必填） */
    private String flowDefKey;

    /** 表单数据（JSON 字符串，可选） */
    private String formData;

    // ─── 调度参数 ───

    /** 上次执行时间 */
    private LocalDateTime lastFireTime;

    /** 下次执行时间 */
    private LocalDateTime nextFireTime;
}
