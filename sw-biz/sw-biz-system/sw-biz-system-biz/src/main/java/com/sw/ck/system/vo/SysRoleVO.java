package com.sw.ck.system.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

import com.sw.ck.system.entity.SysRole;

/**
 * SysRole 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class SysRoleVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String name;
    private String code;
    private Integer sort;
    private Integer status;
    private Integer dataScope;
    private List<Long> deptIds;
    private Boolean builtIn;
    private String description;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static SysRoleVO from(SysRole source) {
        if (source == null) {
            return null;
        }
        SysRoleVO target = new SysRoleVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
