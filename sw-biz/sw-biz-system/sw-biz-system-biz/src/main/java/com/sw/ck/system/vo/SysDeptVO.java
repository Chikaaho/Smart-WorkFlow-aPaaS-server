package com.sw.ck.system.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.system.entity.SysDept;

/**
 * SysDept 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class SysDeptVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private Long parentId;
    private String name;
    private String code;
    private Integer sort;
    private Integer status;
    private Long leaderId;
    private String tenantName;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static SysDeptVO from(SysDept source) {
        if (source == null) {
            return null;
        }
        SysDeptVO target = new SysDeptVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
