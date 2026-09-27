package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.controller.MenuManageVO;
import com.sw.ck.system.entity.SysMenu;
import com.sw.ck.system.mapper.SysMenuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单管理服务（V012-BUG-011）：菜单管理页的读取与受控更新。
 * <p>
 * 只开放展示与导航体验字段（title/icon/sort/hidden）的更新；路由身份字段
 * （name/path/component/menu_type/permission）不允许经此入口修改，避免破坏
 * 菜单-路由-权限契约。新增/删除菜单仍走迁移，保持 schema 治理口径。
 * </p>
 */
@Service
public class MenuManageService {

    private final SysMenuMapper sysMenuMapper;

    public MenuManageService(SysMenuMapper sysMenuMapper) {
        this.sysMenuMapper = sysMenuMapper;
    }

    /** 全量目录/菜单条目（不含按钮），按 parentId + sort 排序，前端组树。 */
    public List<MenuManageVO> manageItems() {
        List<SysMenu> menus = sysMenuMapper.selectList(
                Wrappers.lambdaQuery(SysMenu.class)
                        .in(SysMenu::getMenuType, List.of(0, 1))
                        .orderByAsc(SysMenu::getParentId)
                        .orderByAsc(SysMenu::getSort));
        return menus.stream().map(this::toVo).toList();
    }

    /**
     * 受控更新：title/icon/sort/hidden（null 字段跳过）。
     * 按钮行与不存在的行拒绝更新。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePermitted(long id, String title, String icon, Integer sort, Boolean hidden) {
        SysMenu menu = sysMenuMapper.selectById(id);
        if (menu == null || menu.getMenuType() != null && menu.getMenuType() == 2) {
            throw new BaseException(1000, "菜单不存在或不允许编辑");
        }
        if (title != null) {
            menu.setTitle(title);
        }
        if (icon != null) {
            menu.setIcon(icon);
        }
        if (sort != null) {
            menu.setSort(sort);
        }
        if (hidden != null) {
            menu.setHidden(hidden);
        }
        menu.setUpdateTime(LocalDateTime.now());
        sysMenuMapper.updateById(menu);
    }

    private MenuManageVO toVo(SysMenu menu) {
        return MenuManageVO.builder()
                .id(String.valueOf(menu.getId()))
                .parentId(menu.getParentId() == null || menu.getParentId() == 0L
                        ? null : String.valueOf(menu.getParentId()))
                .name(menu.getName())
                .title(menu.getTitle())
                .path(menu.getPath())
                .icon(menu.getIcon())
                .sort(menu.getSort())
                .hidden(menu.getHidden())
                .menuType(menu.getMenuType())
                .build();
    }
}
