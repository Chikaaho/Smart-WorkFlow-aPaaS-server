package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotEventRule;

/**
 * IotEventRule 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotEventRuleVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String code;
    private String name;
    private Long deviceId;
    private String ruleType;
    private String conditionJson;
    private Long debounceMs;
    private Long cooldownMs;
    private Integer continuousCount;
    private Long scriptId;
    private String processTemplateKey;
    private String formMappingJson;
    private Integer processEnabled;
    private String status;
    private Integer ruleVersion;
    private LocalDateTime lastFiredTime;
    private Integer agentEnabled;
    private String agentGraphKey;
    private String agentInputMappingJson;
    private String agentFailureTemplateKey;
    private String agentFailureConfigJson;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotEventRuleVO from(IotEventRule source) {
        if (source == null) {
            return null;
        }
        IotEventRuleVO target = new IotEventRuleVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
