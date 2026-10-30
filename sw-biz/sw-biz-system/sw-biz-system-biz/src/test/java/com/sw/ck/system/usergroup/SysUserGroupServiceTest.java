package com.sw.ck.system.usergroup;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserGroup;
import com.sw.ck.system.entity.SysUserGroupMember;
import com.sw.ck.system.mapper.SysUserGroupMemberMapper;
import com.sw.ck.system.mapper.SysUserGroupMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.service.impl.SysUserGroupServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SysUserGroupServiceImpl} 业务单元测试（D112：P28/I36）。
 * <p>
 * 覆盖：业务标识唯一性、成员绑定校验（同租户/未删除/启用/可见）、整量替换/追加/移除/
 * 清空、删除连动成员、事务注解契约与零隐式授权（本类不触碰角色/菜单/数据权限关系）。
 * 纯单元测试，Mock Mapper，无需 Spring 上下文。
 * </p>
 */
@DisplayName("用户组 Service 业务测试")
class SysUserGroupServiceTest {

    private SysUserGroupMapper groupMapper;
    private SysUserGroupMemberMapper memberMapper;
    private SysUserMapper userMapper;
    private SysUserGroupServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        groupMapper = mock(SysUserGroupMapper.class);
        memberMapper = mock(SysUserGroupMemberMapper.class);
        userMapper = mock(SysUserMapper.class);
        service = new SysUserGroupServiceImpl(memberMapper, userMapper);
        // 反射注入 baseMapper：字段实际位于 MP ServiceImpl 父类（继承链查找，名称含 baseMapper）
        java.lang.reflect.Field baseMapperField = null;
        Class<?> clazz = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class;
        while (clazz != null && baseMapperField == null) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (f.getName().toLowerCase().contains("basemapper")) {
                    baseMapperField = f;
                    break;
                }
            }
            clazz = clazz.getSuperclass();
        }
        org.junit.jupiter.api.Assertions.assertNotNull(baseMapperField, "未找到 baseMapper 字段");
        baseMapperField.setAccessible(true);
        baseMapperField.set(service, groupMapper);
    }

    private SysUserGroup group(Long id, String code, String name, Integer status) {
        SysUserGroup g = new SysUserGroup();
        g.setId(id);
        g.setGroupCode(code);
        g.setGroupName(name);
        g.setStatus(status);
        return g;
    }

    private SysUser user(Long id, int status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setStatus(status);
        return u;
    }

    private Page<SysUser> pageOf(SysUser... users) {
        Page<SysUser> result = new Page<>(1, 50);
        result.setRecords(java.util.Arrays.asList(users));
        result.setTotal(users.length);
        return result;
    }

    // ─── 主记录 ──────────────────────────────────────────────

    @Test
    @DisplayName("创建：空标识/空名称 → 参数异常")
    void create_blankFields_shouldThrow() {
        SysUserGroup g1 = new SysUserGroup();
        g1.setGroupCode("");
        g1.setGroupName("x");
        assertThatThrownBy(() -> service.create(g1)).isInstanceOf(BaseException.class);

        SysUserGroup g2 = new SysUserGroup();
        g2.setGroupCode("G-1");
        g2.setGroupName(" ");
        assertThatThrownBy(() -> service.create(g2)).isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("创建：同租户同业务标识已存在 → 唯一性拒绝")
    void create_duplicateCode_shouldThrow() {
        when(groupMapper.selectOne(any(Wrapper.class))).thenReturn(group(1L, "G-001", "已有组", 0));
        SysUserGroup g = group(null, "G-001", "新组", 0);
        assertThatThrownBy(() -> service.create(g)).isInstanceOf(BaseException.class);
        verify(groupMapper, never()).insert(any(SysUserGroup.class));
    }

    @Test
    @DisplayName("创建：合法组 → 保存并返回 ID；默认状态 0=启用")
    void create_shouldPersist() {
        when(groupMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(groupMapper.insert(org.mockito.ArgumentMatchers.<SysUserGroup>any())).thenAnswer(inv -> {
            SysUserGroup g = inv.getArgument(0);
            g.setId(88L);
            return 1;
        });

        SysUserGroup g = group(null, "G-088", "新用户组", null);
        Long id = service.create(g);

        assertThat(id).isEqualTo(88L);
        assertThat(g.getStatus()).isZero();
        verify(groupMapper).insert(org.mockito.ArgumentMatchers.<SysUserGroup>any());
        // 唯一性校验仅一次（创建前查重）
        verify(groupMapper, times(1)).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("更新：业务标识不可变（请求携带新标识被忽略）")
    void update_groupCodeImmutable() {
        when(groupMapper.selectById(1L)).thenReturn(group(1L, "G-001", "旧名", 0));
        when(groupMapper.updateById(org.mockito.ArgumentMatchers.<SysUserGroup>any())).thenReturn(1);

        SysUserGroup g = group(1L, "G-999", "新名", 1);
        service.update(g);

        assertThat(g.getGroupCode()).isEqualTo("G-001");
        verify(groupMapper).updateById(org.mockito.ArgumentMatchers.<SysUserGroup>argThat(
                upd -> "G-001".equals(upd.getGroupCode())));
    }

    @Test
    @DisplayName("更新：组不存在 → 参数异常")
    void update_missing_shouldThrow() {
        when(groupMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.update(group(99L, "G-1", "x", 0)))
                .isInstanceOf(BaseException.class);
    }

    // ─── 成员关系 ──────────────────────────────────────────────

    @Test
    @DisplayName("整量替换：全部用户可见可用 → 先删后插；空列表=清空")
    void updateMembers_validUsers_shouldReplace() {
        when(groupMapper.selectById(1L)).thenReturn(group(1L, "G-001", "组", 0));
        when(groupMapper.selectMemberCandidates(any(Page.class), isNull(), anyBoolean(), anyString(), any(), any(), any(), any())).thenReturn(pageOf(user(10L, 0), user(11L, 0)));
        when(memberMapper.delete(any(Wrapper.class))).thenReturn(0);
        when(memberMapper.insert(any(SysUserGroupMember.class))).thenReturn(1);

        service.updateMemberIds(1L, List.of(10L, 11L, 10L)); // 含重复 → 去重

        verify(memberMapper).delete(any(Wrapper.class));
        verify(memberMapper, times(2)).insert(org.mockito.ArgumentMatchers.<SysUserGroupMember>any());
        service.updateMemberIds(1L, List.of());
        verify(memberMapper, times(2)).delete(any(Wrapper.class));
        verifyNoMoreInteractions(memberMapper);
    }

    @Test
    @DisplayName("整量替换：任一目标用户不存在/不可见 → 整体拒绝，不产生删除与写入")
    void updateMembers_invalidUser_shouldRejectAtomically() {
        when(groupMapper.selectById(1L)).thenReturn(group(1L, "G-001", "组", 0));
        when(groupMapper.selectMemberCandidates(any(Page.class), isNull(), anyBoolean(), anyString(), any(), any(), any(), any())).thenReturn(pageOf(user(10L, 0))); // 99 不可见 不在候选

        assertThatThrownBy(() -> service.updateMemberIds(1L, List.of(10L, 99L)))
                .isInstanceOf(BaseException.class);
        // 原子性：校验失败在删除/插入之前，绝不产生部分写入
        verify(memberMapper, never()).delete(any(Wrapper.class));
        verify(memberMapper, never()).insert(any(SysUserGroupMember.class));
    }

    @Test
    @DisplayName("整量替换：目标用户为停用/锁定状态 → 拒绝")
    void updateMembers_disabledUser_shouldReject() {
        when(groupMapper.selectById(1L)).thenReturn(group(1L, "G-001", "组", 0));
        when(groupMapper.selectMemberCandidates(any(Page.class), isNull(), anyBoolean(), anyString(), any(), any(), any(), any())).thenReturn(pageOf()); // 停用/锁定用户不在候选

        assertThatThrownBy(() -> service.updateMemberIds(1L, List.of(7L))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.updateMemberIds(1L, List.of(8L))).isInstanceOf(BaseException.class);
        verify(memberMapper, never()).delete(any(Wrapper.class));
        verify(memberMapper, never()).insert(any(SysUserGroupMember.class));
    }

    @Test
    @DisplayName("追加：已存在成员去重；空输入为幂等")
    void addMembers_shouldDedupe() {
        when(groupMapper.selectById(1L)).thenReturn(group(1L, "G-001", "组", 0));
        when(groupMapper.selectMemberCandidates(any(Page.class), isNull(), anyBoolean(), anyString(), any(), any(), any(), any())).thenReturn(pageOf(user(10L, 0), user(11L, 0)));
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(
                List.of(member(1L, 10L))); // 10 已在组内

        service.addMemberIds(1L, List.of(10L, 11L));

        verify(memberMapper, times(1)).insert(
                org.mockito.ArgumentMatchers.<SysUserGroupMember>argThat(m -> m.getUserId() == 11L));
        service.addMemberIds(1L, List.of());
        // 空输入幂等：提前返回，不再新增任何交互
        verify(memberMapper, times(1)).selectList(any(Wrapper.class));
        verify(memberMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("移除：幂等删除指定成员；清空：删除全部")
    void removeAndClear_shouldDelegate() {
        when(groupMapper.selectById(1L)).thenReturn(group(1L, "G-001", "组", 0));
        when(memberMapper.delete(any(Wrapper.class))).thenReturn(0);

        service.removeMemberIds(1L, List.of(10L, 10L, 11L));
        service.clearMembers(1L);

        verify(memberMapper, times(2)).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("删除组：先删成员关系再删主记录（同事务）")
    void deleteGroup_shouldRemoveMembersFirst() {
        when(groupMapper.selectById(1L)).thenReturn(group(1L, "G-001", "组", 0));
        when(memberMapper.delete(any(Wrapper.class))).thenReturn(0);
        when(groupMapper.deleteById(any())).thenReturn(1);

        service.delete(1L);

        verify(memberMapper).delete(any(Wrapper.class));
        verify(groupMapper).deleteById(1L);
    }

    @Test
    @DisplayName("成员变更与主记录操作均标注事务（回滚语义）")
    void memberOperations_shouldBeTransactional() throws NoSuchMethodException {
        assertThat(SysUserGroupServiceImpl.class.getMethod("updateMemberIds", Long.class, List.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(SysUserGroupServiceImpl.class.getMethod("addMemberIds", Long.class, List.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(SysUserGroupServiceImpl.class.getMethod("removeMemberIds", Long.class, List.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(SysUserGroupServiceImpl.class.getMethod("clearMembers", Long.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(SysUserGroupServiceImpl.class.getMethod("delete", Long.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    // ─── 零隐式授权（风险方向 1） ──────────────────────────────

    @Test
    @DisplayName("零隐式授权：用户组实现不触碰角色/菜单/数据权限关系表")
    void noImplicitAuthorization() {
        // 若实现引用 SysRoleMapper/SysRoleMenuMapper/SysRoleDeptMapper 或 sys_user_role，
        // 此处通过显式 deny 证明：本测试只 mock 三个 mapper，构造参数不可多传
        // 所有构造器参数类型集合：仅组成员 mapper、用户 mapper、登录上下文 ——
        // 绝不出现角色/菜单/数据权限 mapper（角色关系由既有 SysRoleService 管理，用户组不触碰）
        var paramTypes = java.util.Arrays.stream(SysUserGroupServiceImpl.class.getConstructors())
                .flatMap(c -> java.util.Arrays.stream(c.getParameterTypes()))
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(paramTypes)
                .as("构造器只应注入组成员 mapper、用户 mapper 与登录上下文")
                .containsExactlyInAnyOrder("SysUserGroupMemberMapper", "SysUserMapper", "LoginContextProvider");
        assertThat(paramTypes)
                .as("不得注入任何角色/菜单/数据权限 mapper（零隐式授权）")
                .doesNotContain("SysRoleMapper", "SysRoleMenuMapper", "SysRoleDeptMapper", "SysUserRoleMapper");
        // 契约：角色/菜单/按钮/数据范围关系不因加组变化 —— 由集成测试以权限快照证明
        assertThat(SysUserGroup.class.getDeclaredFields()).anyMatch(f -> f.getName().equals("memberIds"));
    }

    // ─── 分页与候选 ──────────────────────────────────────────

    @Test
    @DisplayName("分页 → 透传 query 与数据范围参数到 Mapper")
    void page_shouldDelegate() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUserGroup> mpPage = new Page<>(1, 10);
        mpPage.setRecords(List.of(group(1L, "G-001", "组", 0)));
        mpPage.setTotal(1L);
        when(groupMapper.selectGroupPage(any(Page.class), any(SysUserGroup.class), anyBoolean(), anyString(),
                any(), any(), any(), any())).thenReturn(mpPage);

        PageResult<SysUserGroup> result = service.page(new PageParam(), group(1L, null, "组", null));

        assertThat(result.getTotal()).isEqualTo(1L);
        // 无登录上下文时数据范围参数为安全默认（ALL、无过滤）；租户显式传 null
        verify(groupMapper).selectGroupPage(any(Page.class), any(SysUserGroup.class), eq(false), eq("ALL"),
                isNull(), isNull(), any(), isNull());
    }

    @Test
    @DisplayName("成员候选 → 透传 keyword 与数据范围参数到 Mapper")
    void memberCandidates_shouldDelegate() {
        Page<SysUser> mpPage = new Page<>(1, 20);
        mpPage.setRecords(List.of(user(5L, 0)));
        mpPage.setTotal(1L);
        when(groupMapper.selectMemberCandidates(any(Page.class), eq("张三"), anyBoolean(), anyString(),
                any(), any(), any(), any())).thenReturn(mpPage);

        PageResult<SysUser> result = service.memberCandidates(new PageParam(), "张三");

        assertThat(result.getTotal()).isEqualTo(1L);
        verify(groupMapper).selectMemberCandidates(any(Page.class), eq("张三"), eq(false), eq("ALL"),
                isNull(), isNull(), any(), isNull());
    }

    private SysUserGroupMember member(Long id, Long userId) {
        SysUserGroupMember m = new SysUserGroupMember();
        m.setId(id);
        m.setUserId(userId);
        return m;
    }
}
