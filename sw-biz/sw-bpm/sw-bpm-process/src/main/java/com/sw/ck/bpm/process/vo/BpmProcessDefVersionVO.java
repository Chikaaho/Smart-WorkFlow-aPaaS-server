package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmProcessDefVersion;

/**
 * BpmProcessDefVersion 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmProcessDefVersionVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long defId;
    private Integer graphVersion;
    private String status;
    private String name;
    private String formKey;
    private String formVersion;
    private String functionVersions;
    private String deploymentId;
    private String processDefinitionId;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private String publisherName;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmProcessDefVersionVO from(BpmProcessDefVersion source) {
        if (source == null) {
            return null;
        }
        BpmProcessDefVersionVO target = new BpmProcessDefVersionVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
