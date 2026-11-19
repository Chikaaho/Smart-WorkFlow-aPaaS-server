package com.sw.ck.bpm.process.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;

/**
 * DynamicBranchSnapshot 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class DynamicBranchSnapshotVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String processInstanceId;
    private String nodeKey;
    private Integer branchIndex;
    private String sourceType;
    private String sourceValue;
    private String deptIds;
    private Long leaderId;
    private String convergeMode;
    private String status;
    private String taskId;
    private String cancelReason;
    private Integer semanticVersion;
    private String objectType;
    private String objectId;
    private Long roundNo;
    private String executionId;
    private String sourceRefs;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static DynamicBranchSnapshotVO from(DynamicBranchSnapshot source) {
        if (source == null) {
            return null;
        }
        DynamicBranchSnapshotVO target = new DynamicBranchSnapshotVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
