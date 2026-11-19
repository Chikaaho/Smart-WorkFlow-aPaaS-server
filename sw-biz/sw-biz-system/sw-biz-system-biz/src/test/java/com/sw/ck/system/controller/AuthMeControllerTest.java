package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.service.SysMenuService;
import com.sw.ck.system.service.SysUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link AuthMeController} 单元测试。
 * <p>
 * 覆盖 /me 和 /menus 两条路径：
 * <ul>
 *   <li>超管 happy path</li>
 *   <li>未鉴权 401</li>
 *   <li>超管菜单树（parentId null / id string / directory component null）</li>
 * </ul>
 * 直接 Mock Service 层，无需装载 Spring 上下文。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthMeControllerTest {

    @Mock
    private SysUserService sysUserService;
    @Mock
    private SysMenuService sysMenuService;
    @InjectMocks
    private AuthMeController controller;

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== /me ====================

    @Test
    @DisplayName("超管调 /me → superAdmin=true, roles 含 superadmin, permissions 为空, user 信息完整")
    void me_withSuperAdmin_shouldReturnFullInfo() {
        // -- Arrange：装配 LoginUserHolder（模拟 JwtAuthenticationFilter 认证后的上下文）--
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setTenantId(0L);
        loginUser.setUsername("admin");
        loginUser.setRoles(List.of("superadmin"));
        loginUser.setPermissions(Collections.emptyList());
        loginUser.setSuperAdmin(true);
        LoginUserHolder.set(loginUser);

        // -- Arrange：Mock SysUser 基本信息 --
        SysUser sysUser = new SysUser();
        sysUser.setId(1L);
        sysUser.setUsername("admin");
        sysUser.setRealName("系统管理员");
        sysUser.setDeptId(1L);
        sysUser.setTenantId(0L);
        sysUser.setAvatar("http://avatar.url/admin.png");
        when(sysUserService.getById(1L)).thenReturn(sysUser);

        // -- Act --
        R<AuthMeVO> result = controller.me();

        // -- Assert --
        assertThat(result.getCode())
                .as("成功码应为 0")
                .isZero();
        assertThat(result.getData()).isNotNull();

        // superAdmin
        assertThat(result.getData().getSuperAdmin())
                .as("超管标记应为 true")
                .isTrue();

        // roles
        assertThat(result.getData().getRoles())
                .as("roles 应包含 'superadmin'")
                .contains("superadmin");

        // permissions：超管旁路，返回空数组
        assertThat(result.getData().getPermissions())
                .as("超管 permissions 应为空数组")
                .isEmpty();

        // user 基本信息
        assertThat(result.getData().getUser()).isNotNull();
        assertThat(result.getData().getUser().getId())
                .as("userId 应与 LoginUser 一致")
                .isEqualTo(1L);
        assertThat(result.getData().getUser().getUsername())
                .as("username 应与 LoginUser 一致")
                .isEqualTo("admin");
        assertThat(result.getData().getUser().getDisplayName())
                .as("displayName 应为 realName")
                .isEqualTo("系统管理员");
        assertThat(result.getData().getUser().getDeptId())
                .as("deptId 应与 SysUser 一致")
                .isEqualTo(1L);
        assertThat(result.getData().getUser().getTenantId())
                .as("tenantId 应与 LoginUser 一致")
                .isZero();
        assertThat(result.getData().getUser().getAvatar())
                .as("avatar 应与 SysUser 一致")
                .isEqualTo("http://avatar.url/admin.png");
    }

    @Test
    @DisplayName("未鉴权调 /me → 401")
    void me_withoutAuth_shouldReturn401() {
        // -- Arrange：不设 LoginUserHolder --
        LoginUserHolder.clear();

        // -- Act --
        R<AuthMeVO> result = controller.me();

        // -- Assert --
        assertThat(result.getCode())
                .as("未认证应返回非 0 的失败码")
                .isNotZero();
        assertThat(result.getMsg())
                .as("应包含未认证提示")
                .isNotNull();
    }

    // ==================== /menus ====================

    @Test
    @DisplayName("超管调 /menus → 返回全量菜单树, 根节点 parentId=null, id 为 string, 目录 component=null")
    void menus_withSuperAdmin_shouldReturnFullTree() {
        // -- Arrange：装配 LoginUserHolder --
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setSuperAdmin(true);
        LoginUserHolder.set(loginUser);

        // -- Arrange：构建模拟菜单树（模拟 SysMenuServiceImpl 的返回）--
        // 顶级 1：System（菜单）
        AuthMenuVO system = AuthMenuVO.builder()
                .id("1")
                .parentId(null)
                .name("System")
                .title("系统管理")
                .path("system")
                .component("system/views/SystemHome")
                .icon("Setting")
                .sort(10)
                .menuType(1)
                .permission("system:view")
                .hidden(false)
                .children(List.of())
                .build();

        // 顶级 2：Form（目录，component=null）
        AuthMenuVO formOverview = AuthMenuVO.builder()
                .id("3")
                .parentId("2")
                .name("FormOverview")
                .title("低代码概览")
                .path("form/overview")
                .component("form/views/FormDefList")
                .icon("Document")
                .sort(10)
                .menuType(1)
                .permission("form:view")
                .hidden(false)
                .children(List.of())
                .build();

        AuthMenuVO formDesigner = AuthMenuVO.builder()
                .id("4")
                .parentId("2")
                .name("FormDesigner")
                .title("表单设计")
                .path("form/designer")
                .component("form/views/FormDesigner")
                .icon("EditPen")
                .sort(20)
                .menuType(1)
                .permission("form:design")
                .hidden(false)
                .children(List.of())
                .build();

        AuthMenuVO form = AuthMenuVO.builder()
                .id("2")
                .parentId(null)
                .name("Form")
                .title("低代码")
                .path("form")
                .component(null)   // 目录 → component=null
                .icon("Grid")
                .sort(20)
                .menuType(0)       // 目录
                .permission(null)
                .hidden(false)
                .children(List.of(formOverview, formDesigner))
                .build();

        // 其余 5 个顶级（简化，仅验证树结构）
        AuthMenuVO workflow = AuthMenuVO.builder()
                .id("5").parentId(null).name("Workflow").title("流程引擎")
                .path("workflow").component("workflow/views/WorkflowHome")
                .icon("Share").sort(30).menuType(1).permission("workflow:view").hidden(false)
                .children(List.of()).build();

        AuthMenuVO notify = AuthMenuVO.builder()
                .id("6").parentId(null).name("Notify").title("通知")
                .path("notify").component("notify/views/NotifyHome")
                .icon("Bell").sort(40).menuType(1).permission("notify:view").hidden(false)
                .children(List.of()).build();

        AuthMenuVO agent = AuthMenuVO.builder()
                .id("7").parentId(null).name("Agent").title("智能体")
                .path("agent").component("agent/views/AgentHome")
                .icon("MagicStick").sort(50).menuType(1).permission("agent:view").hidden(false)
                .children(List.of()).build();

        AuthMenuVO iot = AuthMenuVO.builder()
                .id("8").parentId(null).name("Iot").title("物联网")
                .path("iot").component("iot/views/IotHome")
                .icon("Cpu").sort(60).menuType(1).permission("iot:view").hidden(false)
                .children(List.of()).build();

        AuthMenuVO openapi = AuthMenuVO.builder()
                .id("9").parentId(null).name("Openapi").title("开放接口")
                .path("openapi").component("openapi/views/OpenapiHome")
                .icon("Connection").sort(70).menuType(1).permission("openapi:view").hidden(false)
                .children(List.of()).build();

        List<AuthMenuVO> mockTree = List.of(system, form, workflow, notify, agent, iot, openapi);

        when(sysMenuService.getMenuTree(1L, true)).thenReturn(mockTree);

        // -- Act --
        R<List<AuthMenuVO>> result = controller.menus();

        // -- Assert --
        assertThat(result.getCode())
                .as("成功码应为 0")
                .isZero();

        List<AuthMenuVO> tree = result.getData();
        assertThat(tree)
                .as("超管应返回 7 个顶级节点")
                .hasSize(7);

        // 验证根节点 parentId 序列化为 null（不是 "0"/0）
        for (AuthMenuVO node : tree) {
            assertThat(node.getParentId())
                    .as("根节点 %s 的 parentId 应为 null", node.getName())
                    .isNull();
        }

        // 验证 id 为 String 类型
        assertThat(tree.get(0).getId())
                .as("id 应为 String 类型")
                .isInstanceOf(String.class);

        // 验证 "低代码" 目录：component=null, menuType=0, 含 2 子
        AuthMenuVO formNode = tree.stream()
                .filter(n -> "Form".equals(n.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 Form 节点"));

        assertThat(formNode.getComponent())
                .as("目录节点 component 应为 null")
                .isNull();
        assertThat(formNode.getMenuType())
                .as("目录节点 menuType 应为 0")
                .isZero();
        assertThat(formNode.getChildren())
                .as("Form 应有 2 个子节点")
                .hasSize(2);

        // 验证子节点 parentId 为 "2"（String）
        for (AuthMenuVO child : formNode.getChildren()) {
            assertThat(child.getParentId())
                    .as("低代码子节点 %s 的 parentId 应为 \"2\"", child.getName())
                    .isEqualTo("2");
        }

        // 验证菜单节点 component 非 null
        assertThat(tree.get(0).getComponent())
                .as("菜单节点 component 应非 null")
                .isNotNull();
    }

    @Test
    @DisplayName("未鉴权调 /menus → 401")
    void menus_withoutAuth_shouldReturn401() {
        // -- Arrange：不设 LoginUserHolder --
        LoginUserHolder.clear();

        // -- Act --
        R<List<AuthMenuVO>> result = controller.menus();

        // -- Assert --
        assertThat(result.getCode())
                .as("未认证应返回非 0 的失败码")
                .isNotZero();
        assertThat(result.getMsg())
                .as("应包含未认证提示")
                .isNotNull();
    }

    @Test
    @DisplayName("普通用户调 /menus → 返回空树（本环未 seed sys_role_menu）")
    void menus_withNormalUser_shouldReturnEmptyTree() {
        // -- Arrange：装配普通用户 LoginUserHolder --
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(2L);
        loginUser.setSuperAdmin(false);
        LoginUserHolder.set(loginUser);

        when(sysMenuService.getMenuTree(2L, false)).thenReturn(Collections.emptyList());

        // -- Act --
        R<List<AuthMenuVO>> result = controller.menus();

        // -- Assert --
        assertThat(result.getCode())
                .as("成功码应为 0")
                .isZero();
        assertThat(result.getData())
                .as("普通用户应返回空树")
                .isEmpty();
    }
}
