package com.sw.ck.security.holder;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

@Data
public class LoginUser implements Serializable {

    private Long userId;
    private String username;
    private Long tenantId;
    private List<String> roles;
    private List<String> permissions;

    /**
     * 所属部门，数据范围为 DEPT / DEPT_AND_CHILD 时作为起点。
     */
    private Long deptId;

    /**
     * 数据范围。由 {@link com.sw.ck.security.spi.UserDetailsProvider} 在认证后回查填充，
     * 供 Prompt 4 的数据范围拦截使用，本模块不做任何拦截逻辑。
     */
    private DataScope dataScope;

    /**
     * dataScope == CUSTOM 时生效，自定义可见部门集合。
     */
    private Set<Long> customDeptIds;

    /**
     * 超管短路标识：true 时数据权限/权限校验均应直接放行，由调用方（Prompt 4）判断短路。
     */
    private boolean superAdmin;

    /**
     * 首次登录强制改密标记（1.0.0 F1-4）：导入/管理员新建的账号在完成首次改密前，
     * 服务端拒绝除改密链路（refresh/logout/改密/自身信息）外的全部业务请求。
     * 由身份装载从 sys_user.must_change_password 回查填充，改密成功后随缓存刷新失效为 false。
     */
    private boolean mustChangePassword;
}
