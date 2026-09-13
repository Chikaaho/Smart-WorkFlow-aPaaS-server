package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.entity.SysUserRole;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.mapper.SysUserPostMapper;
import com.sw.ck.system.entity.SysUserPost;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.UserPostAssociation;
import com.sw.ck.system.service.UserPageQuery;
import com.sw.ck.security.cache.LoginUserLoader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 系统用户 Service 实现。
 */
@Service
public class SysUserServiceImpl
        extends BaseServiceImpl<SysUserMapper, SysUser>
        implements SysUserService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SysUserServiceImpl.class);

    private final PasswordEncoder passwordEncoder;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysUserPostMapper sysUserPostMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysPostMapper sysPostMapper;
    private final SysDeptMapper sysDeptMapper;

    /** 可选协同组件：启停/撤权/删号后立即踢出登录缓存，保证权限即时收敛。 */
    private final LoginUserLoader loginUserLoader;

    public SysUserServiceImpl(PasswordEncoder passwordEncoder) {
        this(passwordEncoder, null, null, null, null, null, null);
    }

    public SysUserServiceImpl(PasswordEncoder passwordEncoder, SysUserRoleMapper sysUserRoleMapper,
                              SysUserPostMapper sysUserPostMapper, SysRoleMapper sysRoleMapper,
                              SysPostMapper sysPostMapper) {
        this(passwordEncoder, sysUserRoleMapper, sysUserPostMapper, sysRoleMapper, sysPostMapper, null, null);
    }

    @Autowired
    public SysUserServiceImpl(PasswordEncoder passwordEncoder, SysUserRoleMapper sysUserRoleMapper,
                              SysUserPostMapper sysUserPostMapper, SysRoleMapper sysRoleMapper,
                              SysPostMapper sysPostMapper, SysDeptMapper sysDeptMapper,
                              @org.springframework.context.annotation.Lazy LoginUserLoader loginUserLoader) {
        this.passwordEncoder = passwordEncoder;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysUserPostMapper = sysUserPostMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysPostMapper = sysPostMapper;
        this.sysDeptMapper = sysDeptMapper;
        this.loginUserLoader = loginUserLoader;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysUser user, String plainPassword) {
        Objects.requireNonNull(plainPassword, "密码不能为空");
        // 用户名唯一性前置校验：唯一索引兜底会以 DuplicateKeyException 形式漏出为 500
        if (user.getUsername() != null && getByUsername(user.getUsername()) != null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户名已存在");
        }
        user.setPassword(passwordEncoder.encode(plainPassword));
        save(user);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWithAssociations(SysUser user, String plainPassword, List<Long> roleIds, List<UserPostAssociation> posts) {
        Long id = create(user, plainPassword);
        updateRoleIds(id, roleIds);
        updatePosts(id, posts);
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysUser user, String plainPassword) {
        if (plainPassword != null && !plainPassword.isEmpty()) {
            user.setPassword(passwordEncoder.encode(plainPassword));
        } else {
            // 不修改密码：从 DB 加载旧密码保留
            SysUser existing = getById(user.getId());
            if (existing != null) {
                user.setPassword(existing.getPassword());
            }
        }
        updateById(user);
        // 启停/部门调整/资料变更后立即收敛该用户会话与权限
        kickOut(user.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWithAssociations(SysUser user, String plainPassword, List<Long> roleIds, List<UserPostAssociation> posts) {
        update(user, plainPassword);
        // null = 本次更新未提及该关联，保持不变；空数组才表示清空。
        // 否则部分更新（如启停/改名）会静默摧毁既有角色/任职（I1 G5b 证据链发现）
        if (roleIds != null) {
            updateRoleIds(user.getId(), roleIds);
        }
        if (posts != null) {
            updatePosts(user.getId(), posts);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 解除角色/岗位关联（逻辑删保留轨迹），再删用户并立即收敛会话
        if (sysUserRoleMapper != null) {
            sysUserRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, id));
        }
        if (sysUserPostMapper != null) {
            sysUserPostMapper.delete(Wrappers.lambdaQuery(SysUserPost.class).eq(SysUserPost::getUserId, id));
        }
        removeById(id);
        kickOut(id);
    }

    @Override
    public PageResult<SysUser> page(PageParam pageParam) {
        // 数据范围条件由 @DataScope 标注的 selectUserPage 经 DataScopeHandler 自动拼接
        return PageResult.of(baseMapper.selectUserPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize())));
    }

    @Override
    public PageResult<SysUser> page(PageParam pageParam, UserPageQuery query) {
        return PageResult.of(baseMapper.selectUserPageByQuery(new Page<>(pageParam.getPageNum(), pageParam.getPageSize()), query));
    }

    @Override
    public SysUser getByUsername(String username) {
        // 登录前无租户上下文：用户名全局唯一（uk 不含 tenant_id），必须跨租户解析，否则非 0 租户账号无法认证
        return baseMapper.selectGlobalByUsername(username);
    }

    @Override
    public SysUser getById(Long id) {
        return baseMapper.selectById(id);
    }

    @Override
    public List<Long> listRoleIds(Long userId) {
        return sysUserRoleMapper.selectList(Wrappers.lambdaQuery(SysUserRole.class)
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoleIds(Long userId, List<Long> roleIds) {
        if (sysUserRoleMapper == null) return;
        List<Long> valid = roleIds == null ? List.of() : roleIds.stream().filter(Objects::nonNull).distinct()
                .map(id -> sysRoleMapper.selectById(id)).filter(Objects::nonNull)
                .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                .filter(r -> !"superadmin".equals(r.getCode())).map(SysRole::getId).toList();
        if (roleIds != null && valid.size() != roleIds.stream().filter(Objects::nonNull).distinct().count())
            throw new IllegalArgumentException("只能绑定启用的普通角色");
        sysUserRoleMapper.delete(Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId));
        // 唯一键含 deleted 列：先物理清除历史软删残留，重复更新（如停用→恢复）才能再次插入
        purgeSoftDeletedRoleLinks(userId);
        valid.forEach(roleId -> {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            sysUserRoleMapper.insert(relation);
        });
        // 撤权/授权立即生效
        kickOut(userId);
    }

    @Override
    public List<UserPostAssociation> listPosts(Long userId) {
        if (sysUserPostMapper == null) return List.of();
        return sysUserPostMapper.selectList(Wrappers.lambdaQuery(SysUserPost.class).eq(SysUserPost::getUserId, userId))
                .stream()
                .filter(row -> row.getPostId() != null)
                .map(row -> new UserPostAssociation(row.getPostId(), row.getDeptId()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePosts(Long userId, List<UserPostAssociation> posts) {
        if (sysUserPostMapper == null) return;
        List<SysUserPost> rows = buildPostRows(userId, posts);
        sysUserPostMapper.delete(Wrappers.lambdaQuery(SysUserPost.class).eq(SysUserPost::getUserId, userId));
        purgeSoftDeletedPostLinks(userId);
        rows.forEach(sysUserPostMapper::insert);
        // 岗位任职变化立即收敛（岗位供流程选人消费，属组织权威数据）
        kickOut(userId);
    }

    /**
     * 校验并构造任职行：岗位必须启用；deptId 为空回落用户主部门，
     * 非空必须是本租户内未删除部门；按 (postId, deptId) 去重。
     */
    private List<SysUserPost> buildPostRows(Long userId, List<UserPostAssociation> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        Long fallbackDeptId = null;
        List<SysUserPost> rows = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (UserPostAssociation association : posts) {
            if (association == null || association.getPostId() == null) {
                continue;
            }
            SysPost post = sysPostMapper.selectById(association.getPostId());
            if (post == null || post.getStatus() == null || post.getStatus() != 1) {
                throw new IllegalArgumentException("只能绑定启用的岗位");
            }
            Long deptId = association.getDeptId();
            if (deptId == null) {
                if (fallbackDeptId == null) {
                    SysUser user = getById(userId);
                    fallbackDeptId = user == null ? null : user.getDeptId();
                }
                deptId = fallbackDeptId;
            } else if (sysDeptMapper != null) {
                SysDept dept = sysDeptMapper.selectById(deptId);
                if (dept == null) {
                    throw new BaseException(CommonErrorCode.PARAM_ERROR, "任职部门不存在或已删除");
                }
            }
            Long effectiveDeptId = deptId == null ? 0L : deptId;
            String dedupeKey = association.getPostId() + ":" + effectiveDeptId;
            if (seen.contains(dedupeKey)) {
                continue;
            }
            seen.add(dedupeKey);
            SysUserPost row = new SysUserPost();
            row.setUserId(userId);
            row.setPostId(association.getPostId());
            row.setDeptId(effectiveDeptId);
            rows.add(row);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(Long userId, String plainPassword) {
        Objects.requireNonNull(plainPassword, "密码不能为空");
        SysUser user = new SysUser();
        user.setId(userId);
        user.setPassword(passwordEncoder.encode(plainPassword));
        updateById(user);
        kickOut(userId);
    }

    /**
     * 权限/身份变更后立即踢出登录缓存；下一次请求回查最新状态（停用即 401）。
     * fail-secure：驱逐失败（缓存中间件不可用等基础设施异常）向上抛出，由调用方事务
     * 回滚，变更明确失败且可审计，杜绝“变更成功但旧会话仍持旧权限”的窗口。
     * 仅容忍登录缓存装配缺失（NoSuchBeanDefinitionException，只出现在手工构造的
     * 非生产上下文；生产由 SecurityAutoConfiguration 恒创建 LoginUserLoader）。
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
            log.error("用户权限/身份变更后踢出登录缓存失败，变更将回滚 userId={}", userId, e);
            throw e;
        }
    }

    private void purgeSoftDeletedRoleLinks(Long userId) {
        if (sysUserRoleMapper == null) return;
        sysUserRoleMapper.hardDeleteSoftDeletedByUser(userId, currentTenantId());
    }

    private void purgeSoftDeletedPostLinks(Long userId) {
        if (sysUserPostMapper == null) return;
        sysUserPostMapper.hardDeleteSoftDeletedByUser(userId, currentTenantId());
    }

    private Long currentTenantId() {
        com.sw.ck.security.holder.LoginUser loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
        if (loginUser == null || loginUser.getTenantId() == null) {
            // 软删链接清理按租户隔离；租户上下文缺失属异常路径，fail closed 不落租户 0
            throw new IllegalStateException("用户关联清理缺少租户上下文");
        }
        return loginUser.getTenantId();
    }
}
