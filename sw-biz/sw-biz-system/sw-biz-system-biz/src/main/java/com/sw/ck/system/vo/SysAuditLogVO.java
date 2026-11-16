package com.sw.ck.system.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.system.entity.SysAuditLog;

/**
 * SysAuditLog 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class SysAuditLogVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long tenantId;
    private String logType;
    private String username;
    private Long userId;
    private String result;
    private String failReason;
    private String module;
    private String action;
    private String target;
    private String ip;
    private String userAgent;
    private String exceptionType;
    private String summary;
    private String traceId;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static SysAuditLogVO from(SysAuditLog source) {
        if (source == null) {
            return null;
        }
        SysAuditLogVO target = new SysAuditLogVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
