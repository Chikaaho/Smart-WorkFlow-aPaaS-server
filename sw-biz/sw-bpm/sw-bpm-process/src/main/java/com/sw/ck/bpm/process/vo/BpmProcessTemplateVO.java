package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmProcessTemplate;

/**
 * BpmProcessTemplate 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmProcessTemplateVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String name;
    private String category;
    private String description;
    private String formKey;
    private String graphJson;
    private Integer templateVersion;
    private String status;
    private String scopeType;
    private Long scopeDeptId;
    private Long sourceDefId;
    private Integer sourceDefVersion;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmProcessTemplateVO from(BpmProcessTemplate source) {
        if (source == null) {
            return null;
        }
        BpmProcessTemplateVO target = new BpmProcessTemplateVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
