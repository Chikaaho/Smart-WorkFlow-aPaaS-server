package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.service.SysMenuService;
import com.sw.ck.system.service.SysUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前认证主体会话端点。
 * <p>
 * {@code GET /system/auth/me}（兼容别名 {@code GET /auth/me}）返回当前登录用户的基本信息、
 * 角色、权限标识及超管标记。{@code GET /system/auth/menus}（兼容别名 {@code GET /auth/menus}）
 * 返回当前用户的导航菜单树。
 * 这些路径不在 {@code sw.security.permit-urls} 白名单中，未携带/失效 token → 401。
 * </p>
 */
@RestController
@RequestMapping({"/system/auth", "/auth"})
public class AuthMeController {

    private final SysUserService sysUserService;
    private final SysMenuService sysMenuService;

    public AuthMeController(SysUserService sysUserService,
                            SysMenuService sysMenuService) {
        this.sysUserService = sysUserService;
        this.sysMenuService = sysMenuService;
    }

    /**
     * 获取当前登录用户会话信息。
     *
     * @return 含 user / roles / permissions / superAdmin 的 R
     */
    @GetMapping("/me")
    public R<AuthMeVO> me() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            return R.fail(401, "未登录或 token 已失效");
        }

        SysUser sysUser = sysUserService.getById(loginUser.getUserId());
        if (sysUser == null) {
            return R.fail(401, "用户不存在");
        }

        AuthMeVO.UserVO userVO = AuthMeVO.UserVO.builder()
                .id(sysUser.getId())
                .username(sysUser.getUsername())
                .displayName(sysUser.getRealName())
                .deptId(sysUser.getDeptId())
                .tenantId(loginUser.getTenantId())
                .avatar(sysUser.getAvatar())
                .build();

        AuthMeVO vo = AuthMeVO.builder()
                .user(userVO)
                .roles(loginUser.getRoles())
                .permissions(loginUser.getPermissions())
                .superAdmin(loginUser.isSuperAdmin())
                .build();

        return R.ok(vo);
    }

    /**
     * 获取当前登录用户的导航菜单树。
     * <p>
     * 超管（superAdmin=true）→ 返回全量菜单树；
     * 非超管 → 经角色-菜单过滤后返回授权菜单树（本环未 seed sys_role_menu，普通用户返回空树属预期）。
     * </p>
     *
     * @return 菜单树根节点列表
     */
    @GetMapping("/menus")
    public R<List<AuthMenuVO>> menus() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            return R.fail(401, "未登录或 token 已失效");
        }

        List<AuthMenuVO> menuTree = sysMenuService.getMenuTree(
                loginUser.getUserId(), loginUser.isSuperAdmin());
        return R.ok(menuTree);
    }
}
