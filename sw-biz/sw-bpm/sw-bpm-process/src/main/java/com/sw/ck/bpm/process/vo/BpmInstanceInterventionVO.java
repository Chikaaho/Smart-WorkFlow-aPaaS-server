package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmInstanceIntervention;

/**
 * BpmInstanceIntervention 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmInstanceInterventionVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String processInstanceId;
    private String action;
    private Long operatorId;
    private String reason;
    private String beforeState;
    private String afterState;
    private Long fromAssignee;
    private Long toAssignee;
    private Integer affectedTasks;
    private LocalDateTime intervenedAt;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmInstanceInterventionVO from(BpmInstanceIntervention source) {
        if (source == null) {
            return null;
        }
        BpmInstanceInterventionVO target = new BpmInstanceInterventionVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
