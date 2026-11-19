package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotDeviceCommand;

/**
 * IotDeviceCommand 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotDeviceCommandVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String productId;
    private String deviceName;
    private String deviceKey;
    private String commandType;
    private String commandKey;
    private String semanticMode;
    private String payload;
    private String status;
    private String idempotentKey;
    private LocalDateTime expiryTime;
    private Integer retryCount;
    private String lastError;
    private String tencentRequestId;
    private String clientToken;
    private String deviceOutput;
    private String result;
    private String approvalBizId;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotDeviceCommandVO from(IotDeviceCommand source) {
        if (source == null) {
            return null;
        }
        IotDeviceCommandVO target = new IotDeviceCommandVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
