package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * IoT 设备实体。
 * <p>
 * 腾讯云设备身份固定为 {@code productId + deviceName}，二者均不可为空。
 * {@code deviceKey} 保留为本地业务别名，不参与腾讯 SDK 请求。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_device")
public class IotDevice extends BaseEntity {

    /**
     * 腾讯云产品 ID（租户内唯一，与 deviceName 组合定位设备）。
     */
    @TableField("product_id")
    private String productId;

    /**
     * 腾讯云设备名称（在所属产品内唯一）。
     */
    @TableField("device_name")
    private String deviceName;

    /**
     * 本地业务标识（保留兼容，不参与腾讯 SDK 请求）。
     */
    @TableField("device_key")
    private String deviceKey;

    /**
     * 设备显示名称。
     */
    @TableField("name")
    private String name;

    /**
     * 设备类型（如 switch / sensor）。
     */
    @TableField("device_type")
    private String deviceType;

    /**
     * 设备在线状态：OFFLINE / ONLINE。
     */
    @TableField("status")
    private String status;

    /**
     * 腾讯云设备在线状态：offline / online / not_active。
     */
    @TableField("tencent_status")
    private String tencentStatus;

    /**
     * 最近在线时间。
     */
    @TableField("last_online_time")
    private LocalDateTime lastOnlineTime;

    /**
     * 连接配置 ID（P21 平台化扩展）。
     */
    @TableField("connection_id")
    private Long connectionId;

    /**
     * 本地产品 ID（P21 平台化扩展）。
     */
    @TableField("product_ref_id")
    private Long productRefId;

    /**
     * 管理状态：DRAFT / PUBLISHED / DISABLED / RETIRED。
     */
    @TableField("manage_status")
    private String manageStatus;

    /**
     * 可接入流程使用开关：1/0。
     */
    @TableField("process_access_enabled")
    private Integer processAccessEnabled;

    /**
     * 最后上报时间。
     */
    @TableField("last_report_time")
    private LocalDateTime lastReportTime;

    /**
     * 最后命令时间。
     */
    @TableField("last_command_time")
    private LocalDateTime lastCommandTime;

    /**
     * 标签（逗号分隔）。
     */
    @TableField("labels")
    private String labels;

    /**
     * 扩展 JSON。
     */
    @TableField("ext_json")
    private String extJson;
}
