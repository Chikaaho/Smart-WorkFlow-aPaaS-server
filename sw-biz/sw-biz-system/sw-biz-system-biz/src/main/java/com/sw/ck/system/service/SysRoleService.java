package com.sw.ck.system.service;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseService;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysUser;

import java.util.List;

/**
 * 系统角色 Service。
 */
public interface SysRoleService extends BaseService<SysRole> {

    /**
     * 创建角色。
     */
    Long create(SysRole role);

    /**
     * 更新角色。
     */
    void update(SysRole role);

    /**
     * 删除角色（逻辑删除）。
     */
    void delete(Long id);

    /**
     * 分页查询角色。
     */
    PageResult<SysRole> page(PageParam pageParam, SysRole query);

    /**
     * 根据编码获取角色。
     */
    SysRole getByCode(String code);

    List<Long> listMenuIds(Long roleId);

    void updateMenuIds(Long roleId, List<Long> menuIds);

    /**
     * 分页查询角色成员（角色↔用户成员维护的反向视图）。
     */
    PageResult<SysUser> pageMembers(Long roleId, PageParam pageParam);
}
