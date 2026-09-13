package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 一次性授权 state（I5）。
 * <p>
 * 每次授权发起签发：不可预测、限时、与租户/Provider/发起会话绑定；回调时原子
 * 消费（consumed=1），重放/过期/篡改/Provider 或租户错配均拒绝。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_sso_auth_state")
public class SsoAuthState extends BaseEntity {

    /** 不可预测 state 值（全局唯一） */
    @TableField("state_value")
    private String stateValue;

    /** 发起时的 Provider（错配即拒绝） */
    @TableField("provider")
    private String provider;

    /** Provider 支持时的 nonce */
    @TableField("nonce")
    private String nonce;

    /** 授权完成后的受控回跳路径（仅站内相对路径） */
    @TableField("redirect_path")
    private String redirectPath;

    /** 消费标记：0=未消费 1=已消费 */
    @TableField("consumed")
    private Integer consumed;

    /** 过期时间 */
    @TableField("expire_at")
    private LocalDateTime expireAt;
}
