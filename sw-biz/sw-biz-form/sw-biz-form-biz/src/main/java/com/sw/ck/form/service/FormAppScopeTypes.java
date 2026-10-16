package com.sw.ck.form.service;

/**
 * 应用可见范围维度常量（0.1.7 #1）。
 * <p>
 * 多维度判定口径（任一命中即可见）：
 * <ul>
 *   <li>USER —— 用户 ID（sys_user.id）</li>
 *   <li>DEPT —— 用户主属部门 ID（LoginUser.deptId）</li>
 *   <li>ROLE —— 角色 code（LoginUser.roles 装载口径为角色 code，非角色 ID）</li>
 *   <li>POST —— 岗位 ID（sys_user_post 关联）</li>
 *   <li>TENANT —— 租户 ID（LoginUser.tenantId，平台侧跨租户授权用）</li>
 * </ul>
 * 维度值统一字符串化存储（{@code scope_id VARCHAR(64)}）。
 * </p>
 */
public final class FormAppScopeTypes {

    /** 指定用户 */
    public static final String USER = "USER";
    /** 指定部门（用户主属部门） */
    public static final String DEPT = "DEPT";
    /** 指定角色（角色 code） */
    public static final String ROLE = "ROLE";
    /** 指定岗位（sys_user_post 关联） */
    public static final String POST = "POST";
    /** 指定租户（整租户可见） */
    public static final String TENANT = "TENANT";

    private FormAppScopeTypes() {
    }
}
