package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmDraft;

/**
 * BpmDraft 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmDraftVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String title;
    private String formKey;
    private Long formVersion;
    private String processDefKey;
    private String payload;
    private String status;
    private Long commandId;
    private Integer submitSeq;
    private String resultRecordId;
    private String lastError;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmDraftVO from(BpmDraft source) {
        if (source == null) {
            return null;
        }
        BpmDraftVO target = new BpmDraftVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
