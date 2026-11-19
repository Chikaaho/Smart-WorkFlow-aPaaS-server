package com.sw.ck.system.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.system.controller.AuthMenuVO;
import com.sw.ck.system.entity.SysMenu;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysRoleMenu;
import com.sw.ck.system.entity.SysUserRole;
import com.sw.ck.system.mapper.SysMenuMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.service.SysMenuService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜单树服务实现。
 * <p>
 * 树形组装算法：先按 sort 升序排列全量，再用 {@code parent_id=0} 识别根节点，
 * 通过 Map{@code <id, VO>} 将子节点挂到父节点 children 下。
 * VO 边界转换（id/parentId String 化、component 目录按钮置 null）在 {@link #toVo(SysMenu)} 中完成。
 * </p>
 */
@Service
public class SysMenuServiceImpl implements SysMenuService {

    @Autowired
    private SysMenuMapper sysMenuMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Override
    public List<AuthMenuVO> getMenuTree(Long userId, boolean superAdmin) {
        List<SysMenu> menus;

        if (superAdmin) {
            // 超管旁路：返回全量菜单（未删除行），按 sort 升序
            menus = sysMenuMapper.selectList(
                    Wrappers.lambdaQuery(SysMenu.class)
                            .orderByAsc(SysMenu::getSort));
        } else {
            // 非超管：经用户角色 → 角色菜单 → 菜单过滤
            List<Long> menuIds = loadMenuIdsByUserId(userId);
            if (menuIds.isEmpty()) {
                return Collections.emptyList();
            }
            // 补全祖先闭包：授权子菜单的父目录/父菜单必须一并返回，
            // 否则 buildTree 中父节点缺失会把子菜单整体丢弃，造成
            // 「已授权却不出现在导航」的授权与菜单不一致。
            // 祖先节点仅用于导航树组装，按钮权限装配（permissions）不经此路径，无扩权。
            menus = sysMenuMapper.selectList(
                    Wrappers.lambdaQuery(SysMenu.class)
                            .in(SysMenu::getId, withAncestors(menuIds))
                            .orderByAsc(SysMenu::getSort));
        }

        if (menus.isEmpty()) {
            return Collections.emptyList();
        }

        return buildTree(menus);
    }

    /**
     * 补全授权菜单的祖先闭包。
     * <p>
     * 输入角色直接授权的菜单 ID，返回这些菜单及其全部祖先（含根）的并集。
     * 逐级向上查 parent_id，已收集的 ID 不再重复查询，安全处理脏数据成环
     * （超过 {@code sys_menu} 合理深度的循环直接终止）。
     * </p>
     */
    private List<Long> withAncestors(List<Long> menuIds) {
        Set<Long> result = new LinkedHashSet<>(menuIds);
        Set<Long> pending = new LinkedHashSet<>(menuIds);
        while (!pending.isEmpty()) {
            List<SysMenu> current = sysMenuMapper.selectList(
                    Wrappers.lambdaQuery(SysMenu.class).in(SysMenu::getId, pending));
            pending.clear();
            for (SysMenu menu : current) {
                Long parentId = menu.getParentId();
                if (parentId == null || parentId == 0L) {
                    continue;
                }
                if (result.add(parentId)) {
                    pending.add(parentId);
                }
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 加载非超管用户有权访问的菜单 ID 列表。
     * 路径：sys_user_role → sys_role（仅启用）→ sys_role_menu → sys_menu.id
     * <p>
     * 与 {@code UserDetailsProviderImpl} 的权限装配保持一致：停用角色（status=0）
     * 不贡献菜单，保证「角色停用 = 有效撤权」的授权语义对称。
     * </p>
     */
    private List<Long> loadMenuIdsByUserId(Long userId) {
        // 1. 查用户关联的角色
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class)
                        .eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 1.5 仅保留启用角色（status=1）：停用角色不贡献菜单，与权限装配对称
        List<SysRole> activeRoles = sysRoleMapper.selectList(
                Wrappers.lambdaQuery(SysRole.class)
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getStatus, 1));
        List<Long> activeRoleIds = activeRoles.stream()
                .map(SysRole::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (activeRoleIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查启用角色关联的菜单
        List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(
                Wrappers.lambdaQuery(SysRoleMenu.class)
                        .in(SysRoleMenu::getRoleId, activeRoleIds));
        if (roleMenus.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. 去重后返回菜单 ID
        return roleMenus.stream()
                .map(SysRoleMenu::getMenuId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 将平铺菜单列表组装为树形结构。
     * <p>
     * 输入已按 sort 升序，所以各层级自然有序。
     * </p>
     *
     * @param menus 已排序的平铺菜单列表
     * @return 根节点列表（parent_id=0 的节点）
     */
    private List<AuthMenuVO> buildTree(List<SysMenu> menus) {
        // 转换为 VO 并建立 id → VO 映射
        Map<Long, AuthMenuVO> nodeMap = new LinkedHashMap<>(menus.size());
        List<AuthMenuVO> roots = new ArrayList<>();

        for (SysMenu menu : menus) {
            AuthMenuVO vo = toVo(menu);
            nodeMap.put(menu.getId(), vo);

            if (menu.getParentId() == null || menu.getParentId() == 0) {
                roots.add(vo);
            }
        }

        // 挂载非根节点到父节点
        for (SysMenu menu : menus) {
            if (menu.getParentId() != null && menu.getParentId() != 0) {
                AuthMenuVO parent = nodeMap.get(menu.getParentId());
                if (parent != null) {
                    List<AuthMenuVO> children = new ArrayList<>(parent.getChildren());
                    children.add(nodeMap.get(menu.getId()));
                    parent.setChildren(children);
                }
            }
        }

        return roots;
    }

    /**
     * SysMenu → AuthMenuVO 边界转换。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>id：bigint → String</li>
     *   <li>parentId：parent_id=0 或 null → null；其它 → String</li>
     *   <li>component：menu_type=0（目录）或 menu_type=2（按钮）→ null</li>
     * </ul>
     * </p>
     */
    private AuthMenuVO toVo(SysMenu menu) {
        String parentId = (menu.getParentId() == null || menu.getParentId() == 0L)
                ? null
                : String.valueOf(menu.getParentId());

        String component = (menu.getMenuType() == null
                || menu.getMenuType() == 0
                || menu.getMenuType() == 2)
                ? null
                : menu.getComponent();

        return AuthMenuVO.builder()
                .id(String.valueOf(menu.getId()))
                .parentId(parentId)
                .name(menu.getName())
                .title(menu.getTitle())
                .path(menu.getPath())
                .component(component)
                .icon(menu.getIcon())
                .sort(menu.getSort())
                .menuType(menu.getMenuType())
                .permission(menu.getPermission())
                .hidden(menu.getHidden())
                .build();
    }
}
