package com.sw.ck.system.controller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /system/auth/me 响应 VO。
 * <p>
 * 对齐前端 §6 契约，不含任何业务逻辑。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthMeVO {

    /** 用户基本信息 */
    private UserVO user;

    /** 权限标识数组；前端 adapter 转 Set */
    private List<String> permissions;

    /** 角色 key（sys_role.code）数组 */
    private List<String> roles;

    /** 是否超管（超级租户 0 身份且角色 code 含 'superadmin'） */
    private Boolean superAdmin;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserVO {
        private Long id;
        private String username;
        private String displayName;
        private Long deptId;
        private Long tenantId;
        private String avatar;
    }
}
