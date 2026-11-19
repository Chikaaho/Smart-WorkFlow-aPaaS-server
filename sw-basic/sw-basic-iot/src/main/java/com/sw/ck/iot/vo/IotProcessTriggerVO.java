package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotProcessTrigger;

/**
 * IotProcessTrigger 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotProcessTriggerVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long ruleId;
    private Long scriptId;
    private Long deviceId;
    private String idempotentKey;
    private String status;
    private String processInstanceId;
    private String formSnapshot;
    private String processTemplateKey;
    private String triggerSource;
    private Long configuredBy;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String error;
    private LocalDateTime triggerTime;
    private String actionType;
    private String agentGraphKey;
    private Long agentExecutionId;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotProcessTriggerVO from(IotProcessTrigger source) {
        if (source == null) {
            return null;
        }
        IotProcessTriggerVO target = new IotProcessTriggerVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
