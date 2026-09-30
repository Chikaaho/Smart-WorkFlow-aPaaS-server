package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 命令效果权威账本（{@code sw_bpm_command_effect}）。
 * <p>
 * 与业务效果<strong>同事务</strong>写入（{@code CommandEffectRecorder}，MANDATORY 传播），
 * 是"业务已提交但命令完成记录未写/被拒"窗口的确定恢复依据：
 * 恢复路径据本行把命令收敛为 COMPLETED，并回传同一结果，不重做业务、不猜测结果。
 * 每命令至多一行（command_id 主键）；重复记录保留首次（效果至多一次）。
 * </p>
 */
@Data
@TableName("sw_bpm_command_effect")
public class BpmCommandEffect {

    /** 命令标识（sw_bpm_command.id；主键，保证效果至多一条）。 */
    @TableId(value = "command_id", type = IdType.INPUT)
    private Long commandId;

    /** 统一逻辑命令身份（与命令受理时冻结值一致）。 */
    @TableField("logical_command_id")
    private String logicalCommandId;

    /** 写入时的领取租约令牌（审计：哪次领取提交了效果）。 */
    @TableField("claim_token")
    private String claimToken;

    /** 权威结果（JSON，与命令完成结果一致）。 */
    @TableField("result_json")
    private String resultJson;

    /** 业务对象引用（记录/任务/批次项等，供回查与审计）。 */
    @TableField("biz_ref")
    private String bizRef;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("create_time")
    private LocalDateTime createTime;
}
