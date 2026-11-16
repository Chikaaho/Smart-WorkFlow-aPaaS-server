package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmHandoverItem;

/**
 * BpmHandoverItem 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmHandoverItemVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long handoverId;
    private String taskId;
    private String processInstanceId;
    private String itemType;
    private Long beforeAssignee;
    private Long afterAssignee;
    private String result;
    private String failReason;
    private Long ruleId;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmHandoverItemVO from(BpmHandoverItem source) {
        if (source == null) {
            return null;
        }
        BpmHandoverItemVO target = new BpmHandoverItemVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
