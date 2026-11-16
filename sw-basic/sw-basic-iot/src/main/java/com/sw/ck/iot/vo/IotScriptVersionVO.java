package com.sw.ck.iot.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.iot.entity.IotScriptVersion;

/**
 * IotScriptVersion 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class IotScriptVersionVO implements Serializable {

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
    private String sourceCode;
    private String status;
    private LocalDateTime publishTime;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static IotScriptVersionVO from(IotScriptVersion source) {
        if (source == null) {
            return null;
        }
        IotScriptVersionVO target = new IotScriptVersionVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
