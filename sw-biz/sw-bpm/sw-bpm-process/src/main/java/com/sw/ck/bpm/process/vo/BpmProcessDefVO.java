package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmProcessDef;

/**
 * BpmProcessDef 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmProcessDefVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String processKey;
    private String name;
    private String formKey;
    private Integer defVersion;
    private String status;
    private String deploymentId;
    private String processDefinitionId;
    private String graphJson;
    private String themeRule;
    private Long categoryId;
    private Boolean iotAccessEnabled;
    private String iotDeviceActionJson;
    private Integer publishedVersion;
    private Long sourceTemplateId;
    private Integer sourceTemplateVersion;
    private String creatorName;
    private String categoryName;
    private String updatedBy;
    private Long instanceCount;
    private String tenantName;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmProcessDefVO from(BpmProcessDef source) {
        if (source == null) {
            return null;
        }
        BpmProcessDefVO target = new BpmProcessDefVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
