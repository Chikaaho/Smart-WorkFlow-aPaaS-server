package com.sw.ck.system.orgfoundation;

import com.sw.ck.system.controller.DeptController;
import com.sw.ck.system.controller.PostController;
import com.sw.ck.system.controller.RoleController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I1 部门/岗位管理端点鉴权契约测试（负向权限验证的静态面）。
 * <p>
 * 方向 §3.1：普通用户不能通过页面、深链或构造请求获得管理能力。
 * Dept/Post 管理端点此前完全无 {@code @PreAuthorize}（登录即可增删改），
 * 本契约钉死：每个端点必须携带与 V66 种子权限串一致的注解。
 * </p>
 */
@DisplayName("I1 部门/岗位端点 @PreAuthorize 契约")
class SystemMgmtEndpointSecurityContractTest {

    @Test
    @DisplayName("DeptController 五个端点全部携带 system:dept:* 鉴权")
    void deptEndpoints_shouldDeclarePermissions() throws Exception {
        Map<String, String> permissions = permissionStrings(DeptController.class);
        assertThat(permissions).containsAllEntriesOf(Map.of(
                "tree", "system:dept:list",
                "get", "system:dept:list",
                "create", "system:dept:create",
                "update", "system:dept:update",
                "delete", "system:dept:delete"));
    }

    @Test
    @DisplayName("PostController 五个端点全部携带 system:post:* 鉴权")
    void postEndpoints_shouldDeclarePermissions() throws Exception {
        Map<String, String> permissions = permissionStrings(PostController.class);
        assertThat(permissions).containsAllEntriesOf(Map.of(
                "page", "system:post:list",
                "get", "system:post:list",
                "create", "system:post:create",
                "update", "system:post:update",
                "delete", "system:post:delete"));
    }

    @Test
    @DisplayName("RoleController 角色成员视图携带 system:role:list 鉴权")
    void roleMembersEndpoint_shouldDeclarePermission() throws Exception {
        Map<String, String> permissions = permissionStrings(RoleController.class);
        assertThat(permissions).containsEntry("members", "system:role:list");
    }

    private Map<String, String> permissionStrings(Class<?> controllerClass) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Arrays.stream(controllerClass.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
                .forEach(m -> {
                    String value = m.getAnnotation(PreAuthorize.class).value();
                    // 形如 @ss.hasPermi('system:dept:list')
                    int start = value.indexOf('\'');
                    int end = value.lastIndexOf('\'');
                    result.put(m.getName(), start >= 0 && end > start
                            ? value.substring(start + 1, end) : value);
                });
        return result;
    }
}
