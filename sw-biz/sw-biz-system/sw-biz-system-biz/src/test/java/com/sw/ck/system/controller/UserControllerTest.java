package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.api.user.UserOptionDTO;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.impl.UserFacadeImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link UserController} 单元测试。
 * <p>
 * 覆盖分页/详情/创建/更新/删除五个端点，验证请求参数传递和 R 包装正确性。
 * 纯单元测试，Mock SysUserService，无需装载 Spring 上下文。
 * </p>
 */
@DisplayName("用户管理控制器测试")
class UserControllerTest {

    private final SysUserService sysUserService = mock(SysUserService.class);
    private final com.sw.ck.system.api.user.UserQueryFacade userQueryFacade =
            mock(com.sw.ck.system.api.user.UserQueryFacade.class);
    private final UserController controller = new UserController(sysUserService, userQueryFacade);

    // ==================== GET /options — 选择器候选（V012-BUG-019） ====================

    @Test
    @DisplayName("options → 命中返回最小展示字段列表，limit 封顶 200")
    void options_shouldReturnMinimalOptions() {
        UserOptionDTO opt = new UserOptionDTO(1L, "admin", "系统管理员");
        when(userQueryFacade.searchActiveUsers(eq("系统"), eq(200)))
                .thenReturn(java.util.Optional.of(List.of(opt)));

        var resp = controller.options("系统", 500);

        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getId()).isEqualTo(1L);
        assertThat(resp.getData().get(0).getRealName()).isEqualTo("系统管理员");
    }

    @Test
    @DisplayName("options → 门面返回 empty（无租户上下文）时回退空列表而非失败")
    void options_shouldFallBackToEmptyOnEmptyFacade() {
        when(userQueryFacade.searchActiveUsers(any(), anyInt())).thenReturn(java.util.Optional.empty());

        var resp = controller.options(null, 50);

        assertThat(resp.getData()).isEmpty();
    }

    // ==================== POST /page — 分页 ====================

    @Test
    @DisplayName("分页查询 → 返回 PageResult 含 records/total/pageNum/pageSize")
    void page_shouldReturnPageResult() {
        SysUser user1 = new SysUser();
        user1.setId(1L);
        user1.setUsername("admin");
        SysUser user2 = new SysUser();
        user2.setId(2L);
        user2.setUsername("zhangsan");

        PageResult<SysUser> mockPage = new PageResult<>();
        mockPage.setRecords(List.of(user1, user2));
        mockPage.setTotal(2L);
        mockPage.setPageNum(1L);
        mockPage.setPageSize(10L);

        when(sysUserService.page(any(PageParam.class))).thenReturn(mockPage);

        R<PageResult<SysUser>> result = controller.page(1, 10, null);

        assertThat(result.getCode()).as("成功码应为 0").isZero();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getRecords()).hasSize(2);
        assertThat(result.getData().getTotal()).isEqualTo(2L);
        verify(sysUserService).page(any(PageParam.class));
    }

    @Test
    @DisplayName("分页 query=null → 不抛异常")
    void page_withNullQuery_shouldNotThrow() {
        when(sysUserService.page(any(PageParam.class))).thenReturn(new PageResult<>());

        R<PageResult<SysUser>> result = controller.page(1, 10, null);

        assertThat(result.getCode()).isZero();
    }

    // ==================== GET /{id} — 详情 ====================

    @Test
    @DisplayName("GET /{id} → 返回用户详情")
    void get_shouldReturnUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRealName("系统管理员");
        user.setEmail("admin@example.com");

        when(sysUserService.getById(1L)).thenReturn(user);

        R<SysUser> result = controller.get(1L);

        assertThat(result.getCode()).as("成功码为 0 → 成功获取").isZero();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getId()).isEqualTo(1L);
        assertThat(result.getData().getUsername()).isEqualTo("admin");
        assertThat(result.getData().getRealName()).isEqualTo("系统管理员");
        verify(sysUserService).getById(1L);
    }

    // ==================== POST — 创建 ====================

    @Test
    @DisplayName("创建用户 → 返回新 ID，plainPassword 透传 Service")
    void create_shouldReturnId() {
        when(sysUserService.createWithAssociations(any(SysUser.class), eq("P@ssw0rd!"), isNull(), isNull())).thenReturn(100L);

        UserController.UserFormRequest req = new UserController.UserFormRequest();
        req.setUsername("newuser");
        req.setRealName("新用户");
        req.setEmail("new@example.com");
        req.setPlainPassword("P@ssw0rd!");

        R<Long> result = controller.create(req);

        assertThat(result.getCode()).as("成功码应为 0").isZero();
        assertThat(result.getData()).as("应返回新用户 ID").isEqualTo(100L);
        verify(sysUserService).createWithAssociations(any(SysUser.class), eq("P@ssw0rd!"), isNull(), isNull());
    }

    @Test
    @DisplayName("创建用户 plainPassword=null → Controller 透传不抛异常")
    void create_withNullPassword_shouldNotThrow() {
        when(sysUserService.createWithAssociations(any(SysUser.class), eq(null), isNull(), isNull())).thenReturn(101L);

        UserController.UserFormRequest req = new UserController.UserFormRequest();
        req.setUsername("user2");
        req.setPlainPassword(null);

        R<Long> result = controller.create(req);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(101L);
    }

    // ==================== PUT — 更新 ====================

    @Test
    @DisplayName("更新用户 → 返回 R.ok() code=0 data=null")
    void update_shouldReturnOk() {
        doNothing().when(sysUserService).updateWithAssociations(any(SysUser.class), nullable(String.class), isNull(), isNull());

        UserController.UserFormRequest req = new UserController.UserFormRequest();
        req.setId(1L);
        req.setUsername("admin");
        req.setRealName("管理员改名");
        req.setPlainPassword(null); // 不修改密码

        R<Void> result = controller.update(req);

        assertThat(result.getCode()).as("成功码应为 0").isZero();
        assertThat(result.getData()).as("无数据体").isNull();
        verify(sysUserService).updateWithAssociations(any(SysUser.class), nullable(String.class), isNull(), isNull());
    }

    // ==================== DELETE /{id} — 删除 ====================

    @Test
    @DisplayName("DELETE /{id} → 返回 R.ok()")
    void delete_shouldReturnOk() {
        doNothing().when(sysUserService).delete(1L);

        R<Void> result = controller.delete(1L);

        assertThat(result.getCode()).as("成功码应为 0").isZero();
        assertThat(result.getData()).as("无数据体").isNull();
        verify(sysUserService).delete(1L);
    }

    @Test
    @DisplayName("用户角色关系 → 支持读取、解绑并透传 userId")
    void roles_shouldReadAndWrite() {
        when(sysUserService.listRoleIds(1L)).thenReturn(List.of(2L));

        R<List<Long>> read = controller.roles(1L);
        R<Void> write = controller.updateRoles(1L, List.of());

        assertThat(read.getData()).containsExactly(2L);
        assertThat(write.getCode()).isZero();
        verify(sysUserService).listRoleIds(1L);
        verify(sysUserService).updateRoleIds(1L, List.of());
    }

    @Test
    @DisplayName("用户角色写端点 → 受 system:user:update 权限保护")
    void roleBindingEndpoint_shouldHaveUpdatePermission() throws NoSuchMethodException {
        assertThat(UserController.class.getMethod("updateRoles", Long.class, List.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:user:update')");
    }
}
