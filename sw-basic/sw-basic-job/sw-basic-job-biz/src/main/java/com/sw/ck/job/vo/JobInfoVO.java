package com.sw.ck.job.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.job.entity.JobInfo;

/**
 * JobInfo 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class JobInfoVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String jobName;
    private String jobGroup;
    private String jobType;
    private String cronExpression;
    private String status;
    private Boolean concurrent;
    private Integer misfirePolicy;
    private String description;
    private String beanName;
    private String beanParams;
    private String flowDefKey;
    private String formData;
    private LocalDateTime lastFireTime;
    private LocalDateTime nextFireTime;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static JobInfoVO from(JobInfo source) {
        if (source == null) {
            return null;
        }
        JobInfoVO target = new JobInfoVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
