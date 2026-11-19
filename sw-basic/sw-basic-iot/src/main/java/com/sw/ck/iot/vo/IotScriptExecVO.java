package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotScriptExec;

/**
 * IotScriptExec 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotScriptExecVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long scriptId;
    private Integer scriptVersion;
    private String triggerSource;
    private String triggerRef;
    private String deviceRef;
    private String status;
    private String inputJson;
    private String outputJson;
    private String error;
    private Long durationMs;
    private Integer sideEffect;
    private String idempotentKey;
    private String correlationId;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotScriptExecVO from(IotScriptExec source) {
        if (source == null) {
            return null;
        }
        IotScriptExecVO target = new IotScriptExecVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
