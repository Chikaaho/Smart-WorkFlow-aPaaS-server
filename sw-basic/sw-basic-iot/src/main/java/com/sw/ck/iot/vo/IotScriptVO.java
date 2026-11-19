package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotScript;

/**
 * IotScript 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotScriptVO implements Serializable {

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
    private String language;
    private String triggerType;
    private String bindingJson;
    private String inputSchema;
    private String outputSchema;
    private String status;
    private Integer currentVersion;
    private Integer publishedVersion;
    private Integer timeoutMs;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotScriptVO from(IotScript source) {
        if (source == null) {
            return null;
        }
        IotScriptVO target = new IotScriptVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
