package com.sw.ck.job.event;

import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * FLOW 类型定时任务的流程启动意图。
 *
 * <p>当 {@code job_type=FLOW} 的定时任务到达执行时间时，调度器构造本意图对象并交给
 * {@code ScheduledFlowStartPort}（由 bpm 模块实现）在<b>调度器显式建立的事务内</b>持久化。
 * 意图一旦持久化，即由持久命令队列的领取/重试/恢复状态机负责最终启动一次流程。</p>
 *
 * <h3>幂等去重键</h3>
 * 使用 {@code jobId + fireTime} 作为幂等去重键：同一任务同一次触发只对应一个持久命令，
 * 重复触发、进程重启后的恢复重放都不会重复创建流程。
 *
 * @see com.sw.ck.job.port.ScheduledFlowStartPort
 */
@Getter
@ToString
public class ScheduledFlowTriggerEvent implements Serializable {

    /** 定时任务 ID（sw_job_info.id） */
    private final Long jobId;

    /** 流程定义 Key */
    private final String flowDefKey;

    /** 表单数据（JSON 字符串） */
    private final String formData;

    /** 触发时间（用于幂等去重） */
    private final LocalDateTime fireTime;

    /** 租户 ID */
    private final Long tenantId;

    /** 任务配置人（受理后的流程发起人身份；取自 sw_job_info.create_by） */
    private final Long configuredBy;

    public ScheduledFlowTriggerEvent(Long jobId, String flowDefKey, String formData,
                                     LocalDateTime fireTime, Long tenantId) {
        this(jobId, flowDefKey, formData, fireTime, tenantId, null);
    }

    public ScheduledFlowTriggerEvent(Long jobId, String flowDefKey, String formData,
                                     LocalDateTime fireTime, Long tenantId, Long configuredBy) {
        this.jobId = jobId;
        this.flowDefKey = flowDefKey;
        this.formData = formData;
        this.fireTime = fireTime;
        this.tenantId = tenantId;
        this.configuredBy = configuredBy;
    }

    /**
     * 触发时间的 epoch 毫秒（幂等键的稳定组成）。
     * <p>fireTime 为空时返回 {@code 0}，调用方应以“缺少幂等身份”拒绝受理。</p>
     */
    public long fireTimeEpochMillis() {
        return fireTime == null ? 0L : fireTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
