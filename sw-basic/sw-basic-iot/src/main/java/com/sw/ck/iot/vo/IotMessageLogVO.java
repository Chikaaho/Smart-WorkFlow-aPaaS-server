package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotMessageLog;

/**
 * IotMessageLog 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotMessageLogVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long connId;
    private Long deviceId;
    private String topic;
    private String direction;
    private String messageId;
    private String dedupKey;
    private String payload;
    private String payloadType;
    private String parseStatus;
    private String parseError;
    private Integer qos;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotMessageLogVO from(IotMessageLog source) {
        if (source == null) {
            return null;
        }
        IotMessageLogVO target = new IotMessageLogVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
