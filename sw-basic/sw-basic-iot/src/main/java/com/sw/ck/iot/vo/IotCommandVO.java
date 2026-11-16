package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotCommand;

/**
 * IotCommand 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotCommandVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long deviceId;
    private Long connId;
    private String provider;
    private String capabilityType;
    private String capabilityId;
    private String paramsJson;
    private String status;
    private String resultJson;
    private String error;
    private String idempotentKey;
    private String correlationId;
    private String sourceType;
    private String sourceRef;
    private String flowInstanceId;
    private String flowTaskId;
    private Integer qos;
    private LocalDateTime sentTime;
    private LocalDateTime replyTime;
    private Integer timeoutMs;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotCommandVO from(IotCommand source) {
        if (source == null) {
            return null;
        }
        IotCommandVO target = new IotCommandVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
