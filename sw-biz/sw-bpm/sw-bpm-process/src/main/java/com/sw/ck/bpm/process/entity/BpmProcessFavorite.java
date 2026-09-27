package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程收藏（V012-BUG-009）：用户级收藏，唯一键 (tenant_id, user_id, process_key)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_process_favorite")
public class BpmProcessFavorite extends BaseEntity {

    /** 收藏用户 */
    @TableField("user_id")
    private Long userId;

    /** 流程标识（processDefKey） */
    @TableField("process_key")
    private String processKey;

    /** 收藏时流程名称快照 */
    @TableField("process_name")
    private String processName;
}
