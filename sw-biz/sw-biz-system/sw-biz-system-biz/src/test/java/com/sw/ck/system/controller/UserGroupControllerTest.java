package com.sw.ck.system.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserGroup;
import com.sw.ck.system.service.SysUserGroupService;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link UserGroupController} 单元测试（D112：P28/I36 用户组管理）。
 * <p>
 * 覆盖分页/详情/创建/更新/启停/删除/成员读写/候选八个端点，验证请求参数传递、
 * DTO 转换与 R 包装正确性；权限注解契约单独断言（查看=list、管理=manage）。
 * 纯单元测试，Mock SysUserGroupService，无需装载 Spring 上下文。
 * </p>
 */
@DisplayName("用户组管理控制器测试")
class UserGroupControllerTest {

    private final SysUserGroupService sysUserGroupService = mock(SysUserGroupService.class);
    private final UserGroupController controller = new UserGroupController(sysUserGroupService);

    private SysUserGroup group(Long id, String code, String name, int status) {
        SysUserGroup g = new SysUserGroup();
        g.setId(id);
        g.setGroupCode(code);
        g.setGroupName(name);
        g.setStatus(status);
        return g;
    }

    // ==================== POST /page — 分页 ====================

    @Test
    @DisplayName("分页查询 → 返回 PageResult，query 透传 Service")
    void page_shouldReturnPageResult() {
        PageResult<SysUserGroup> mockPage = new PageResult<>();
        mockPage.setRecords(List.of(group(1L, "G-001", "技术委员会", 0)));
        mockPage.setTotal(1L);
        mockPage.setPageNum(1L);
        mockPage.setPageSize(10L);

        when(sysUserGroupService.page(any(PageParam.class), any(SysUserGroup.class))).thenReturn(mockPage);

        SysUserGroup query = new SysUserGroup();
        query.setGroupName("技术");
        R<PageResult<SysUserGroup>> result = controller.page(1, 10, query);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getRecords()).hasSize(1);
        assertThat(result.getData().getTotal()).isEqualTo(1L);
        assertThat(result.getData().getRecords().get(0).getGroupCode()).isEqualTo("G-001");
        verify(sysUserGroupService).page(any(PageParam.class), same(query));
    }

    @Test
    @DisplayName("分页查询 query=null → 不抛异常")
    void page_withNullQuery_shouldNotThrow() {
        when(sysUserGroupService.page(any(PageParam.class), isNull())).thenReturn(new PageResult<>());
        R<PageResult<SysUserGroup>> result = controller.page(1, 10, null);
        assertThat(result.getCode()).isZero();
    }

    // ==================== GET /{id} — 详情 ====================

    @Test
    @DisplayName("GET /{id} → 返回含成员回填的详情")
    void get_shouldReturnDetail() {
        SysUserGroup g = group(1L, "G-001", "技术委员会", 0);
        g.setMemberIds(List.of(2L, 3L));
        when(sysUserGroupService.getDetail(1L)).thenReturn(g);

        R<SysUserGroup> result = controller.get(1L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getMemberIds()).isEqualTo(List.of(2L, 3L));
        verify(sysUserGroupService).getDetail(1L);
    }

    // ==================== POST — 创建 ====================

    @Test
    @DisplayName("创建用户组 → memberIds 随主记录透传，返回新 ID")
    void create_shouldPassMembers() {
        when(sysUserGroupService.create(any(SysUserGroup.class))).thenReturn(100L);

        UserGroupController.UserGroupFormRequest req = new UserGroupController.UserGroupFormRequest();
        req.setGroupCode("G-100");
        req.setGroupName("新用户组");
        req.setStatus(0);
        req.setRemark("备注");
        req.setMemberIds(List.of(1L, 2L));

        R<Long> result = controller.create(req);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(100L);
        verify(sysUserGroupService).create(ArgumentMatchers.<SysUserGroup>argThat(g ->
                "G-100".equals(g.getGroupCode())
                        && "新用户组".equals(g.getGroupName())
                        && g.getMemberIds() != null && g.getMemberIds().equals(List.of(1L, 2L))));
    }

    // ==================== PUT — 更新 ====================

    @Test
    @DisplayName("更新用户组 → 返回 R.ok()，memberIds 不随更新透传（成员走专用端点）")
    void update_shouldReturnOk() {
        doNothing().when(sysUserGroupService).update(any(SysUserGroup.class));

        UserGroupController.UserGroupFormRequest req = new UserGroupController.UserGroupFormRequest();
        req.setId(1L);
        req.setGroupCode("G-001");
        req.setGroupName("技术委员会改名");
        req.setStatus(1);

        R<Void> result = controller.update(req);

        assertThat(result.getCode()).isZero();
        verify(sysUserGroupService).update(ArgumentMatchers.<SysUserGroup>argThat(g ->
                g.getId() == 1L && "技术委员会改名".equals(g.getGroupName()) && g.getMemberIds() == null));
    }

    @Test
    @DisplayName("更新用户组 id=null → 抛参数异常")
    void update_withoutId_shouldThrow() {
        UserGroupController.UserGroupFormRequest req = new UserGroupController.UserGroupFormRequest();
        req.setGroupCode("G-001");
        req.setGroupName("x");

        Assertions.assertThatThrownBy(() -> controller.update(req))
                .isInstanceOf(BaseException.class);
    }

    // ==================== DELETE /{id} / 启停 ====================

    @Test
    @DisplayName("DELETE /{id} → R.ok()")
    void delete_shouldReturnOk() {
        doNothing().when(sysUserGroupService).delete(1L);
        R<Void> result = controller.delete(1L);
        assertThat(result.getCode()).isZero();
        verify(sysUserGroupService).delete(1L);
    }

    @Test
    @DisplayName("启停 → 分别调用 disable/enable")
    void disableEnable_shouldDelegate() {
        R<Void> d = controller.disable(1L);
        R<Void> e = controller.enable(1L);
        assertThat(d.getCode()).isZero();
        assertThat(e.getCode()).isZero();
        verify(sysUserGroupService).disable(1L);
        verify(sysUserGroupService).enable(1L);
    }

    // ==================== 成员读写 ====================

    @Test
    @DisplayName("成员读取 → 返回 ID 列表")
    void members_shouldRead() {
        when(sysUserGroupService.listMemberIds(1L)).thenReturn(List.of(2L, 3L));
        R<List<Long>> result = controller.members(1L);
        assertThat(result.getData()).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("成员整量替换/追加/移除 → 透传 ID 列表")
    void memberWrite_shouldDelegate() {
        doNothing().when(sysUserGroupService).updateMemberIds(anyLong(), anyList());
        doNothing().when(sysUserGroupService).addMemberIds(anyLong(), anyList());
        doNothing().when(sysUserGroupService).removeMemberIds(anyLong(), anyList());

        assertThat(controller.updateMembers(1L, List.of(2L)).getCode()).isZero();
        assertThat(controller.addMembers(1L, List.of(3L)).getCode()).isZero();
        assertThat(controller.removeMembers(1L, List.of(3L)).getCode()).isZero();

        verify(sysUserGroupService).updateMemberIds(1L, List.of(2L));
        verify(sysUserGroupService).addMemberIds(1L, List.of(3L));
        verify(sysUserGroupService).removeMemberIds(1L, List.of(3L));
    }

    // ==================== 候选用户 ====================

    @Test
    @DisplayName("成员候选 → 透传 keyword 并返回分页")
    void candidates_shouldDelegate() {
        PageResult<SysUser> mockPage = new PageResult<>();
        SysUser u = new SysUser();
        u.setId(5L);
        u.setUsername("zhangsan");
        mockPage.setRecords(List.of(u));
        mockPage.setTotal(1L);
        when(sysUserGroupService.memberCandidates(any(PageParam.class), eq("张三"))).thenReturn(mockPage);

        R<PageResult<SysUser>> result = controller.candidates(1, 20, "张三");

        assertThat(result.getData().getRecords()).hasSize(1);
        assertThat(result.getData().getRecords().get(0).getUsername()).isEqualTo("zhangsan");
        verify(sysUserGroupService).memberCandidates(any(PageParam.class), eq("张三"));
    }

    // ==================== 权限注解契约 ====================

    @Test
    @DisplayName("查看类端点 → system:userGroup:list；管理类端点 → system:userGroup:manage")
    void permissionAnnotations_shouldMatchContract() throws NoSuchMethodException {
        assertThat(UserGroupController.class.getMethod("page", long.class, long.class, SysUserGroup.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:list')");
        assertThat(UserGroupController.class.getMethod("get", Long.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:list')");
        assertThat(UserGroupController.class.getMethod("members", Long.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:list')");
        assertThat(UserGroupController.class.getMethod("candidates", long.class, long.class, String.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:list')");

        assertThat(UserGroupController.class.getMethod("create", UserGroupController.UserGroupFormRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
        assertThat(UserGroupController.class.getMethod("update", UserGroupController.UserGroupFormRequest.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
        assertThat(UserGroupController.class.getMethod("disable", Long.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
        assertThat(UserGroupController.class.getMethod("enable", Long.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
        assertThat(UserGroupController.class.getMethod("delete", Long.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
        assertThat(UserGroupController.class.getMethod("updateMembers", Long.class, List.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
        assertThat(UserGroupController.class.getMethod("addMembers", Long.class, List.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
        assertThat(UserGroupController.class.getMethod("removeMembers", Long.class, List.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('system:userGroup:manage')");
    }
}
