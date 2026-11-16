package com.sw.ck.system.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

import com.sw.ck.system.entity.SysUser;

/**
 * SysUser 的对外视图（响应侧；字段与实体的 JSON 序列化口径一致）。
 */
@Data
public class SysUserVO implements Serializable {

    private Long id;
    private LocalDateTime createTime;
    private Long createBy;
    private LocalDateTime updateTime;
    private Long updateBy;
    private Integer deleted;
    private Long version;
    private Long tenantId;
    private String username;
    private String realName;
    private String email;
    private String phone;
    private Integer sex;
    private Integer status;
    private Long deptId;
    private Integer isAdmin;
    private Integer mustChangePassword;
    private String avatar;
    private List<Long> roleIds;
    private List<Long> postIds;
    private String tenantName;
    private boolean protectedAccount;

    /** 由实体构造视图：字段逐个复制，不做业务加工。 */
    public static SysUserVO from(SysUser source) {
        if (source == null) {
            return null;
        }
        SysUserVO target = new SysUserVO();
        org.springframework.beans.BeanUtils.copyProperties(source, target);
        return target;
    }
}
