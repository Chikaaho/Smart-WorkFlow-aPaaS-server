package com.sw.ck.notify.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import com.sw.ck.notify.entity.NotifyMessage;

/**
 * NotifyMessage 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class NotifyMessageVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long recipientId;
    private String title;
    private String content;
    private String bizType;
    private String bizId;
    @JsonProperty("read")
    private Boolean isRead;
    private String channel;
    private String deliveryStatus;
    private String externalMessageId;
    private String failureReason;
    private String idempotencyKey;
    private String eventType;
    private Long occurrenceNo;
    private Long templateId;
    private Integer templateVersion;
    private String linkType;
    private String linkId;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String failureClass;
    private String receiptDigest;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static NotifyMessageVO from(NotifyMessage source) {
        if (source == null) {
            return null;
        }
        NotifyMessageVO target = new NotifyMessageVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
