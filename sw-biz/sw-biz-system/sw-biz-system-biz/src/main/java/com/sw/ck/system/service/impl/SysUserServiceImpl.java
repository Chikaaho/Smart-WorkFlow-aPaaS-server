package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysUser;
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
import com.sw.ck.system.service.UserPageQuery;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

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

    private final PasswordEncoder passwordEncoder;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysUserPostMapper sysUserPostMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysPostMapper sysPostMapper;

    public SysUserServiceImpl(PasswordEncoder passwordEncoder) {
        this(passwordEncoder, null, null, null, null);
    }

    @Autowired
    public SysUserServiceImpl(PasswordEncoder passwordEncoder, SysUserRoleMapper sysUserRoleMapper,
                              SysUserPostMapper sysUserPostMapper, SysRoleMapper sysRoleMapper,
                              SysPostMapper sysPostMapper) {
        this.passwordEncoder = passwordEncoder;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysUserPostMapper = sysUserPostMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysPostMapper = sysPostMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysUser user, String plainPassword) {
        Objects.requireNonNull(plainPassword, "密码不能为空");
        user.setPassword(passwordEncoder.encode(plainPassword));
        save(user);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createWithAssociations(SysUser user, String plainPassword, List<Long> roleIds, List<Long> postIds) {
        Long id = create(user, plainPassword);
        updateRoleIds(id, roleIds);
        updatePostIds(id, postIds);
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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWithAssociations(SysUser user, String plainPassword, List<Long> roleIds, List<Long> postIds) {
        update(user, plainPassword);
        updateRoleIds(user.getId(), roleIds);
        updatePostIds(user.getId(), postIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        removeById(id);
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
        return lambdaQuery().eq(SysUser::getUsername, username).one();
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
    }

    @Override
    public List<Long> listPostIds(Long userId) {
        if (sysUserPostMapper == null) return List.of();
        return sysUserPostMapper.selectList(Wrappers.lambdaQuery(SysUserPost.class).eq(SysUserPost::getUserId, userId))
                .stream().map(SysUserPost::getPostId).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePostIds(Long userId, List<Long> postIds) {
        if (sysUserPostMapper == null) return;
        List<Long> valid = postIds == null ? List.of() : postIds.stream().filter(Objects::nonNull).distinct()
                .map(id -> sysPostMapper.selectById(id)).filter(Objects::nonNull)
                .filter(p -> p.getStatus() != null && p.getStatus() == 1).map(SysPost::getId).toList();
        if (postIds != null && valid.size() != postIds.stream().filter(Objects::nonNull).distinct().count())
            throw new IllegalArgumentException("只能绑定启用的岗位");
        sysUserPostMapper.delete(Wrappers.lambdaQuery(SysUserPost.class).eq(SysUserPost::getUserId, userId));
        purgeSoftDeletedPostLinks(userId);
        valid.forEach(postId -> { SysUserPost r = new SysUserPost(); r.setUserId(userId); r.setPostId(postId); sysUserPostMapper.insert(r); });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(Long userId, String plainPassword) {
        Objects.requireNonNull(plainPassword, "密码不能为空");
        SysUser user = new SysUser();
        user.setId(userId);
        user.setPassword(passwordEncoder.encode(plainPassword));
        updateById(user);
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
        return loginUser == null || loginUser.getTenantId() == null ? 0L : loginUser.getTenantId();
    }
}
