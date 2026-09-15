package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_connection 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_connection")
public class IotConnection extends BaseEntity {

    /** 稳定标识（租户内唯一） */
    @TableField("code")
    private String code;

    /** 名称 */
    @TableField("name")
    private String name;

    /** 连接类型：TENCENT / MQTT */
    @TableField("conn_type")
    private String connType;

    /** 启停：1/0 */
    @TableField("enabled")
    private Integer enabled;

    /** 健康状态：UNKNOWN/HEALTHY/UNHEALTHY */
    @TableField("health_status")
    private String healthStatus;

    /** 最后检查时间 */
    @TableField("last_check_time")
    private LocalDateTime lastCheckTime;

    /** 最后检查结果 */
    @TableField("last_check_result")
    private String lastCheckResult;

    /** MQTT 主机 */
    @TableField("host")
    private String host;

    /** MQTT 端口 */
    @TableField("port")
    private Integer port;

    /** TLS：1/0 */
    @TableField("use_tls")
    private Integer useTls;

    /** 客户端 ID 前缀 */
    @TableField("client_id_prefix")
    private String clientIdPrefix;

    /** 用户名 */
    @TableField("username")
    private String username;

    /** 密码密文（AES-GCM） */
    @TableField("password_cipher")
    private String passwordCipher;

    /** keepalive 秒 */
    @TableField("keepalive")
    private Integer keepalive;

    /** 干净会话：1/0 */
    @TableField("clean_session")
    private Integer cleanSession;

    /** 重连最小间隔秒 */
    @TableField("reconnect_min_sec")
    private Integer reconnectMinSec;

    /** 重连最大间隔秒 */
    @TableField("reconnect_max_sec")
    private Integer reconnectMaxSec;

    /** 腾讯地域（扩展区） */
    @TableField("region")
    private String region;

    /** 腾讯 endpoint（扩展区） */
    @TableField("endpoint")
    private String endpoint;

    /** 腾讯凭证引用（扩展区） */
    @TableField("secret_ref")
    private String secretRef;

    /** 扩展 JSON */
    @TableField("ext_json")
    private String extJson;

    /**
     * 服务端内部传递的明文口令（不映射任何列，禁止持久化/返回）。
     */
    @TableField(exist = false)
    private transient String plainPassword;

}
