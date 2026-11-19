package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmHandover;

/**
 * BpmHandover 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmHandoverVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long fromUserId;
    private Long toUserId;
    private String scopeDefKeys;
    private Boolean includeProxyRules;
    private String status;
    private Integer totalItems;
    private Integer migratedItems;
    private Integer failedItems;
    private Long operatorId;
    private LocalDateTime executedAt;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmHandoverVO from(BpmHandover source) {
        if (source == null) {
            return null;
        }
        BpmHandoverVO target = new BpmHandoverVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
