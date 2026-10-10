package com.sw.ck.system.service.impl;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.entity.SysPostDelegate;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysPostDelegateMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅱ（A07）岗位委托配置校验单测：
 * 自委托/同优先级重叠/循环/超跳配置拒绝；启停生效校验。
 * <p>
 * 桩口径：同优先级重叠走 {@code selectCount}；循环走链走 {@code selectList}——
 * 两者查询通道分离，可独立打桩。
 * </p>
 */
@DisplayName("P64 岗位委托配置校验测试")
class PositionDelegateServiceImplTest {

    private SysPostDelegateMapper delegateMapper;
    private SysPostMapper postMapper;
    private SysDeptMapper deptMapper;
    private PositionDelegateServiceImpl service;

    @BeforeEach
    void setUp() {
        delegateMapper = mock(SysPostDelegateMapper.class);
        postMapper = mock(SysPostMapper.class);
        deptMapper = mock(SysDeptMapper.class);
        // 匿名子类实例初始化器注入受保护 baseMapper（MyBatis-Plus 仓储基类口径）
        service = new PositionDelegateServiceImpl(postMapper, deptMapper) {
            {
                this.baseMapper = delegateMapper;
            }
        };
        when(postMapper.selectById(1L)).thenReturn(post(1L, "P_LEAD", 1));
        when(postMapper.selectById(2L)).thenReturn(post(2L, "P_REVIEW", 1));
        when(postMapper.selectById(3L)).thenReturn(post(3L, "P_ARCHIVE", 1));
        when(deptMapper.selectById(anyLong())).thenReturn(new SysDept());
        when(delegateMapper.selectCount(any())).thenReturn(0L);
        when(delegateMapper.selectList(any())).thenReturn(List.of());
        // 插入回填主键（真实库由 ID 生成器回填）
        when(delegateMapper.insert(any(SysPostDelegate.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SysPostDelegate.class).setId(60L);
            return 1;
        });
    }

    @Test
    @DisplayName("自委托（源=受托）拒绝")
    void selfDelegateRejected() {
        assertThatThrownBy(() -> service.create(delegate(1L, 1L, "ORG", null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("自委托");
    }

    @Test
    @DisplayName("同优先级重叠拒绝：同源岗位已有 ENABLED ORG 关系时再建拒绝；DEPT 精确范围可与 ORG 并存")
    void samePriorityOverlapRejected() {
        when(delegateMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(delegate(1L, 3L, "ORG", null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("同优先级重叠");

        // DEPT 优先级与既有 ORG 并存合法（重叠检查按精确范围独立计）
        when(delegateMapper.selectCount(any())).thenReturn(0L);
        Long id = service.create(delegate(1L, 3L, "DEPT", 10L));
        assertThat(id).isEqualTo(60L);
    }

    @Test
    @DisplayName("循环拒绝：2→1 在 1→2 已存在时配置拒绝（沿有效关系走链回到源岗位）")
    void cycleChainsRejected() {
        when(delegateMapper.selectCount(any())).thenReturn(0L);
        when(delegateMapper.selectList(any())).thenReturn(List.of(existingRow()));
        assertThatThrownBy(() -> service.create(delegate(2L, 1L, "ORG", null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("循环");
    }

    @Test
    @DisplayName("停用关系保存放行校验；启用（changeStatus）时重新执行重叠与循环校验")
    void changeStatusRevalidatesOnEnable() {
        // DISABLED 保存：跳过重叠/循环校验
        SysPostDelegate disabled = delegate(1L, 3L, "ORG", null);
        disabled.setStatus(SysPostDelegate.STATUS_DISABLED);
        service.create(disabled);

        // 启用时重叠重现 → 拒绝
        SysPostDelegate toEnable = new SysPostDelegate();
        toEnable.setId(77L);
        toEnable.setSourcePostId(1L);
        toEnable.setTargetPostId(3L);
        toEnable.setScopeType("ORG");
        toEnable.setStatus(SysPostDelegate.STATUS_DISABLED);
        when(delegateMapper.selectById(77L)).thenReturn(toEnable);
        when(delegateMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.changeStatus(77L, SysPostDelegate.STATUS_ENABLED))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("同优先级重叠");
    }

    private SysPost post(Long id, String code, int status) {
        SysPost post = new SysPost();
        post.setId(id);
        post.setCode(code);
        post.setStatus(status);
        return post;
    }

    private SysPostDelegate delegate(Long source, Long target, String scope, Long deptId) {
        SysPostDelegate delegate = new SysPostDelegate();
        delegate.setSourcePostId(source);
        delegate.setTargetPostId(target);
        delegate.setScopeType(scope);
        delegate.setDeptId(deptId);
        delegate.setStatus(SysPostDelegate.STATUS_ENABLED);
        return delegate;
    }

    private SysPostDelegate existingRow() {
        SysPostDelegate row = delegate(1L, 2L, "ORG", null);
        row.setId(50L);
        return row;
    }
}
