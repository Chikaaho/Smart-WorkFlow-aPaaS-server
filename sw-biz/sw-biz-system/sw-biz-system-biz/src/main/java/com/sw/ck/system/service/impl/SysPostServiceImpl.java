package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.entity.SysUserPost;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.mapper.SysUserPostMapper;
import com.sw.ck.system.service.SysPostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 岗位 Service 实现。
 */
@Service
public class SysPostServiceImpl
        extends BaseServiceImpl<SysPostMapper, SysPost>
        implements SysPostService {

    /** 岗位状态：启用（sys_post 口径：1=启用 0=停用） */
    private static final int STATUS_ENABLED = 1;

    private final SysUserPostMapper sysUserPostMapper;

    public SysPostServiceImpl() {
        this(null);
    }

    @Autowired
    public SysPostServiceImpl(SysUserPostMapper sysUserPostMapper) {
        this.sysUserPostMapper = sysUserPostMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysPost post) {
        requireCode(post);
        requireUniqueCode(post.getCode(), null);
        // sys_post DDL 默认 0（停用）；创建语义必须是「启用」，否则无法被用户绑定
        if (post.getStatus() == null) {
            post.setStatus(STATUS_ENABLED);
        }
        save(post);
        return post.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysPost post) {
        if (getById(post.getId()) == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "岗位不存在或已删除");
        }
        requireCode(post);
        requireUniqueCode(post.getCode(), post.getId());
        updateById(post);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 先解除用户-岗位关联（逻辑删，保留历史轨迹），再删岗位
        if (sysUserPostMapper != null) {
            sysUserPostMapper.delete(new LambdaQueryWrapper<SysUserPost>()
                    .eq(SysUserPost::getPostId, id));
        }
        removeById(id);
    }

    @Override
    public PageResult<SysPost> page(PageParam pageParam, SysPost query) {
        LambdaQueryWrapper<SysPost> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            if (StringUtils.isNotBlank(query.getCode())) {
                wrapper.like(SysPost::getCode, query.getCode());
            }
            if (StringUtils.isNotBlank(query.getName())) {
                wrapper.like(SysPost::getName, query.getName());
            }
            if (query.getStatus() != null) {
                wrapper.eq(SysPost::getStatus, query.getStatus());
            }
        }
        wrapper.orderByAsc(SysPost::getCreateTime);
        return baseMapper.selectPage(pageParam, wrapper);
    }

    private void requireCode(SysPost post) {
        if (StringUtils.isBlank(post.getCode())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "岗位编码不能为空");
        }
    }

    /** 岗位编码本租户内唯一（排除自身；租户隔离由拦截器保证）。 */
    private void requireUniqueCode(String code, Long selfId) {
        List<SysPost> matches = lambdaQuery().eq(SysPost::getCode, code).list();
        SysPost existing = matches.isEmpty() ? null : matches.get(0);
        if (existing != null && (selfId == null || !existing.getId().equals(selfId))) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "岗位编码已存在");
        }
    }
}
