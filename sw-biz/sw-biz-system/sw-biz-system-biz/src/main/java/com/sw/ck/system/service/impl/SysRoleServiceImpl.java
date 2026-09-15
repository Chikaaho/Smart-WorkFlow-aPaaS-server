package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysRoleDept;
import com.sw.ck.system.entity.SysRoleMenu;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserRole;
import com.sw.ck.system.mapper.SysRoleDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.system.service.SysRoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 系统角色 Service 实现。
 */
@Service
public class SysRoleServiceImpl
        extends BaseServiceImpl<SysRoleMapper, SysRole>
        implements SysRoleService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SysRoleServiceImpl.class);

    private final SysRoleDeptMapper sysRoleDeptMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysUserMapper sysUserMapper;

    /** 可选协同组件：角色停用/授权变更后立即踢出成员登录缓存，权限即时收敛。 */
    private final LoginUserLoader loginUserLoader;

    public SysRoleServiceImpl(SysRoleDeptMapper sysRoleDeptMapper) {
        this(sysRoleDeptMapper, null);
    }

    public SysRoleServiceImpl(SysRoleDeptMapper sysRoleDeptMapper, SysRoleMenuMapper sysRoleMenuMapper) {
        this(sysRoleDeptMapper, sysRoleMenuMapper, null, null, null);
    }

    @Autowired
    public SysRoleServiceImpl(SysRoleDeptMapper sysRoleDeptMapper, SysRoleMenuMapper sysRoleMenuMapper,
                              SysUserRoleMapper sysUserRoleMapper, SysUserMapper sysUserMapper,
                              @org.springframework.context.annotation.Lazy LoginUserLoader loginUserLoader) {
        this.sysRoleDeptMapper = sysRoleDeptMapper;
        this.sysRoleMenuMapper = sysRoleMenuMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysUserMapper = sysUserMapper;
        this.loginUserLoader = loginUserLoader;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysRole role) {
        if (Boolean.TRUE.equals(role.getBuiltIn()) || "superadmin".equals(role.getCode())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "不可创建内置超管角色");
        }
        // 校验编码唯一性
        if (getByCode(role.getCode()) != null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "角色编码已存在");
        }
        validateState(role);
        save(role);
        // 角色部门关联（CUSTOM 数据范围的可见部门集合）
        insertRoleDepts(role.getId(), role.getDeptIds());
        return role.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysRole role) {
        assertMutable(role.getId());
        // 校验编码唯一性（排除自身）
        SysRole existing = getByCode(role.getCode());
        if (existing != null && !existing.getId().equals(role.getId())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "角色编码已存在");
        }
        validateState(role);
        updateById(role);
        // 角色部门关联先删后插（事务内）：唯一键不含 deleted，必须物理删
        sysRoleDeptMapper.hardDeleteByRole(role.getId());
        insertRoleDepts(role.getId(), role.getDeptIds());
        // 启停/授权范围变化立即收敛成员权限
        kickOutMembers(role.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        assertMutable(id);
        List<Long> memberIds = listMemberIds(id);
        // 清理角色全部关联（逻辑删，保留可追溯痕迹），再删角色
        if (sysRoleMenuMapper != null) {
            sysRoleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        }
        sysRoleDeptMapper.delete(new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, id));
        if (sysUserRoleMapper != null) {
            sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        }
        removeById(id);
        memberIds.forEach(this::kickOut);
    }

    @Override
    public PageResult<SysUser> pageMembers(Long roleId, PageParam pageParam) {
        if (sysUserMapper == null) {
            return PageResult.empty();
        }
        return PageResult.of(sysUserMapper.selectUsersByRole(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                roleId));
    }

    @Override
    public PageResult<SysRole> page(PageParam pageParam, SysRole query) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            if (StringUtils.isNotBlank(query.getName())) {
                wrapper.like(SysRole::getName, query.getName());
            }
            if (StringUtils.isNotBlank(query.getCode())) {
                wrapper.like(SysRole::getCode, query.getCode());
            }
            if (query.getStatus() != null) {
                wrapper.eq(SysRole::getStatus, query.getStatus());
            }
        }
        wrapper.orderByAsc(SysRole::getCreateTime);
        PageResult<SysRole> pageResult = baseMapper.selectPage(pageParam, wrapper);
        // 列表回填 deptIds（供前端回显）
        fillDeptIds(pageResult.getRecords());
        return pageResult;
    }

    @Override
    public SysRole getById(java.io.Serializable id) {
        SysRole role = super.getById(id);
        if (role != null) {
            role.setDeptIds(listDeptIds(role.getId()));
        }
        return role;
    }

    @Override
    public SysRole getByCode(String code) {
        return lambdaQuery().eq(SysRole::getCode, code).one();
    }

    @Override
    public List<Long> listMenuIds(Long roleId) {
        return sysRoleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMenuIds(Long roleId, List<Long> menuIds) {
        assertMutable(roleId);
        // 关联行是纯配置关系：物理删除，避免逻辑删行占用唯一键导致「撤销后再授权」撞键
        sysRoleMenuMapper.deletePhysicallyByRoleId(roleId);
        if (menuIds == null) {
            return;
        }
        menuIds.stream().filter(Objects::nonNull).distinct().forEach(menuId -> {
            SysRoleMenu relation = new SysRoleMenu();
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            sysRoleMenuMapper.insert(relation);
        });
        // 菜单/按钮授权变化立即收敛成员权限
        kickOutMembers(roleId);
    }

    private void assertMutable(Long roleId) {
        SysRole role = super.getById(roleId);
        if (role != null && Boolean.TRUE.equals(role.getBuiltIn())
                && "superadmin".equals(role.getCode())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "内置超管角色不可修改或删除");
        }
    }

    /** 角色状态与数据范围合法值校验（1=启用 0=停用；dataScope 0-4）。 */
    private void validateState(SysRole role) {
        if (role.getStatus() != null && role.getStatus() != 0 && role.getStatus() != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "非法角色状态值：" + role.getStatus() + "，仅支持 1（启用）/0（停用）");
        }
        if (role.getDataScope() != null && (role.getDataScope() < 0 || role.getDataScope() > 4)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "非法数据范围值：" + role.getDataScope() + "，仅支持 0-4");
        }
    }

    /** 角色成员用户 ID 列表。 */
    private List<Long> listMemberIds(Long roleId) {
        if (sysUserRoleMapper == null) {
            return List.of();
        }
        return sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * 成员反查 + 逐个踢出。fail-secure：反查或驱逐失败必须让授权变更整体失败回滚，
     * 不允许“变更成功但旧会话仍持旧权限”的窗口存在。
     */
    private void kickOutMembers(Long roleId) {
        List<Long> memberIds = listMemberIds(roleId);
        memberIds.forEach(this::kickOut);
    }

    /**
     * fail-secure 踢出：驱逐失败（缓存中间件不可用等基础设施异常）向上抛出，
     * 由调用方事务回滚，权限变更明确失败且可审计。仅容忍登录缓存装配缺失
     * （NoSuchBeanDefinitionException，只出现在手工构造的非生产上下文；生产由
     * SecurityAutoConfiguration 恒创建 LoginUserLoader，不存在该路径）。
     */
    private void kickOut(Long userId) {
        if (userId == null || loginUserLoader == null) {
            return;
        }
        try {
            loginUserLoader.kickOut(userId);
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
            // 非生产装配上下文：无登录缓存可驱逐
        } catch (Exception e) {
            log.error("角色权限变更后踢出成员登录缓存失败，变更将回滚 userId={}", userId, e);
            throw e;
        }
    }

    /**
     * 写入角色部门关联（去重，空/null 集合不写入任何行）。
     */
    private void insertRoleDepts(Long roleId, List<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return;
        }
        List<Long> distinctDeptIds = deptIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        for (Long deptId : distinctDeptIds) {
            SysRoleDept roleDept = new SysRoleDept();
            roleDept.setRoleId(roleId);
            roleDept.setDeptId(deptId);
            sysRoleDeptMapper.insert(roleDept);
        }
    }

    /**
     * 批量回填 deptIds：一次查询本页全部角色的关联，按 roleId 分组。
     */
    private void fillDeptIds(List<SysRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return;
        }
        List<Long> roleIds = roles.stream()
                .map(SysRole::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (roleIds.isEmpty()) {
            return;
        }
        Map<Long, List<Long>> deptIdsByRole = sysRoleDeptMapper.selectList(
                        new LambdaQueryWrapper<SysRoleDept>().in(SysRoleDept::getRoleId, roleIds))
                .stream()
                .filter(rd -> rd.getRoleId() != null && rd.getDeptId() != null)
                .collect(Collectors.groupingBy(SysRoleDept::getRoleId,
                        LinkedHashMap::new,
                        Collectors.mapping(SysRoleDept::getDeptId, Collectors.toList())));
        for (SysRole role : roles) {
            role.setDeptIds(deptIdsByRole.getOrDefault(role.getId(), Collections.emptyList()));
        }
    }

    /**
     * 查询单个角色的关联部门 ID 列表。
     */
    private List<Long> listDeptIds(Long roleId) {
        return sysRoleDeptMapper.selectList(
                        new LambdaQueryWrapper<SysRoleDept>().eq(SysRoleDept::getRoleId, roleId))
                .stream()
                .map(SysRoleDept::getDeptId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
