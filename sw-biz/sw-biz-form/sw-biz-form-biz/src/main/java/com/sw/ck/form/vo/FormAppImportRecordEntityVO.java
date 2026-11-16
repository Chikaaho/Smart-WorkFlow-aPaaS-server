package com.sw.ck.form.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.form.entity.FormAppImportRecordEntity;

/**
 * FormAppImportRecordEntity 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class FormAppImportRecordEntityVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String packageName;
    private String fileName;
    private String appId;
    private String appName;
    private String result;
    private String reportJson;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static FormAppImportRecordEntityVO from(FormAppImportRecordEntity source) {
        if (source == null) {
            return null;
        }
        FormAppImportRecordEntityVO target = new FormAppImportRecordEntityVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
