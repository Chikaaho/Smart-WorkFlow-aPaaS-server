package com.sw.ck.job.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.job.entity.JobLog;

/**
 * JobLog 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class JobLogVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long jobId;
    private String jobName;
    private String jobGroup;
    private String triggerType;
    private String jobParams;
    private String execStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long duration;
    private String resultMsg;
    private String exceptionStack;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static JobLogVO from(JobLog source) {
        if (source == null) {
            return null;
        }
        JobLogVO target = new JobLogVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
