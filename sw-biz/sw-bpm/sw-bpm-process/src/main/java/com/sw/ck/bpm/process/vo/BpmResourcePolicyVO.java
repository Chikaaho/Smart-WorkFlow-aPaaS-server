package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.BpmResourcePolicy;

/**
 * BpmResourcePolicy 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class BpmResourcePolicyVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Integer policyVersion;
    private String status;
    private Boolean enabled;
    private Boolean stopAcceptance;
    private Integer globalMaxOutstanding;
    private Integer tenantMaxOutstanding;
    private Integer prodReserved;
    private Integer oaReserved;
    private Integer sharedCapacity;
    private Integer tenantRatePerSec;
    private Integer tenantBurst;
    private Integer realtimeGlobalConcurrency;
    private Integer realtimeTenantConcurrency;
    private Integer batchSliceItems;
    private Integer batchPollClaimLimit;
    private String remark;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static BpmResourcePolicyVO from(BpmResourcePolicy source) {
        if (source == null) {
            return null;
        }
        BpmResourcePolicyVO target = new BpmResourcePolicyVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
