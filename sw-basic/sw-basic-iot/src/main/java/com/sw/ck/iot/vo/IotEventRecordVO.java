package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotEventRecord;

/**
 * IotEventRecord 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotEventRecordVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long deviceId;
    private String eventId;
    private String correlationId;
    private String payload;
    private LocalDateTime occurTime;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotEventRecordVO from(IotEventRecord source) {
        if (source == null) {
            return null;
        }
        IotEventRecordVO target = new IotEventRecordVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
