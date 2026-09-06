package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 催办记录（v0.0.2 OA）。
 * <p>
 * 发起人对本人运行中实例的催办流水：记录操作者、对象实例、通知目标、结果与说明。
 * {@code result=ACCEPTED} 行用于 10 分钟冷却判定；{@code cooldownKey} 仅 ACCEPTED 行非空，
 * 唯一索引 {@code uk_sw_bpm_urge_cooldown} 提供并发重复受理的数据库级兜底。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_urge_record")
public class UrgeRecord extends BaseEntity {

    /** Flowable 流程实例 ID */
    @TableField("process_instance_id")
    private String processInstanceId;

    /** 催办发起人（必须为实例 initiator，服务层校验） */
    @TableField("initiator_id")
    private Long initiatorId;

    /** 通知目标（催办时实例的当前实际待办人；被拒/冷却时可为空） */
    @TableField("target_user_id")
    private Long targetUserId;

    /** 结果：ACCEPTED（已受理并发送通知）/ COOLDOWN（冷却中拒绝）/ REJECTED（无权/状态不允许） */
    @TableField("result")
    private String result;

    /** 结果说明（冷却剩余时间、拒绝原因等） */
    @TableField("detail")
    private String detail;

    /** 冷却键（tenant:instance:窗口），仅 ACCEPTED 行非空；唯一索引防并发重复受理 */
    @TableField("cooldown_key")
    private String cooldownKey;
}
