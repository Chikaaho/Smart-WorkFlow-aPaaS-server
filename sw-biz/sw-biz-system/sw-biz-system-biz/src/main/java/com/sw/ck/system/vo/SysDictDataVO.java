package com.sw.ck.system.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

import com.sw.ck.system.entity.SysDictData;

/**
 * SysDictData 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class SysDictDataVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String dictCode;
    private String label;
    private String dictValue;
    private Integer sort;
    private Integer status;
    private Integer isDefault;
    private String cssClass;
    private String listClass;
    private String description;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static SysDictDataVO from(SysDictData source) {
        if (source == null) {
            return null;
        }
        SysDictDataVO target = new SysDictDataVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
