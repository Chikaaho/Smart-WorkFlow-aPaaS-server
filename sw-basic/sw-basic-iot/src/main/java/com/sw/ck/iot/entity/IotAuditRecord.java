package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * IoT 行为审计记录。
 * <p>
 * 只保存可追溯的身份、对象和结果元数据，禁止写入连接口令、密文或脚本秘密。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_audit_record")
public class IotAuditRecord extends BaseEntity {

    @TableField("action")
    private String action;

    @TableField("object_type")
    private String objectType;

    @TableField("object_id")
    private String objectId;

    @TableField("result")
    private String result;

    @TableField("actor_id")
    private Long actorId;

    @TableField("system_identity")
    private String systemIdentity;

    @TableField("action_time")
    private LocalDateTime actionTime;

    @TableField("correlation_id")
    private String correlationId;

    @TableField("detail")
    private String detail;
}
