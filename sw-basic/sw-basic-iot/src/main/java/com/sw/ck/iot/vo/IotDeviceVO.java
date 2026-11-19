package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotDevice;

/**
 * IotDevice 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotDeviceVO implements Serializable {

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
    private String name;
    private String deviceType;
    private String status;
    private String tencentStatus;
    private LocalDateTime lastOnlineTime;
    private Long connectionId;
    private Long productRefId;
    private String manageStatus;
    private Integer processAccessEnabled;
    private LocalDateTime lastReportTime;
    private LocalDateTime lastCommandTime;
    private String labels;
    private String extJson;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotDeviceVO from(IotDevice source) {
        if (source == null) {
            return null;
        }
        IotDeviceVO target = new IotDeviceVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
