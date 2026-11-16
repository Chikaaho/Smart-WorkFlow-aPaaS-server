package com.sw.ck.iot.vo;

import lombok.Data;

import com.sw.ck.iot.entity.IotConnection;

import java.time.LocalDateTime;

/**
 * IoT 连接配置对外视图：凭证只回脱敏占位，不回密文/明文。
 * <p>
 * 字段与原 JSON 转换结果保持一致（含审计列），额外提供
 * {@code passwordMasked}/{@code hasPassword}。
 * </p>
 */
@Data
public class IotConnectionVO {

    private Long id;

    private Long tenantId;

    private LocalDateTime createTime;

    private Long createBy;

    private LocalDateTime updateTime;

    private Long updateBy;

    private Integer deleted;

    private Long version;

    private String code;

    private String name;

    private String connType;

    private Integer enabled;

    private String healthStatus;

    private LocalDateTime lastCheckTime;

    private String lastCheckResult;

    private String host;

    private Integer port;

    private Integer useTls;

    private String clientIdPrefix;

    private String username;

    private Integer keepalive;

    private Integer cleanSession;

    private Integer reconnectMinSec;

    private Integer reconnectMaxSec;

    private String region;

    private String endpoint;

    private String secretRef;

    private String extJson;

    private String passwordMasked;

    private Boolean hasPassword;
}
