package com.sw.ck.system.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserGroup;
import com.sw.ck.system.service.SysUserGroupService;

import jakarta.validation.Valid;

import lombok.Data;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import com.sw.ck.system.vo.SysUserVO;
import com.sw.ck.system.vo.SysUserGroupVO;

/**
 * 用户组管理控制器（D112：P28/I36）。
 * <p>
 * 权限边界：查看 {@code system:userGroup:list}，管理（创建/更新/启停/删除/成员）
 * {@code system:userGroup:manage}；未认证 401、缺权 403 由既有 Spring Security 链路统一处理。
 * 用户组只是业务人员集合，不授予任何角色/菜单/按钮/数据范围能力——本控制器不触碰
 * 登录装配与 sys_user_role / sys_role_menu / sys_role_dept。
 * </p>
 */
@RestController
@RequestMapping("/system/user-group")
public class UserGroupController {

    @Autowired
    private SysUserGroupService sysUserGroupService;

    /** 内嵌 DTO：用户组表单（memberIds 用于创建/更新时随主记录事务写入） */
    @Data
    public static class UserGroupFormRequest {
        private Long id;
        @NotBlank(message = "业务标识不能为空")
        private String groupCode;
        @NotBlank(message = "组名称不能为空")
        private String groupName;
        private Integer status;
        private String remark;
        private List<Long> memberIds;
    }

    /** 分页查询用户组（数据范围纳管）。 */
    @PostMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:userGroup:list')")
    public R<PageResult<SysUserGroupVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestBody(required = false) SysUserGroup query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(PageResult.map(sysUserGroupService.page(pageParam, query), SysUserGroupVO::from));
    }

    /** 用户组详情（含成员回填）。id 限数字，避免吞掉 /candidates 等非数字路径。 */
    @GetMapping("/{id:\\d+}")
    @PreAuthorize("@ss.hasPermi('system:userGroup:list')")
    public R<SysUserGroupVO> get(@PathVariable Long id) {
        return R.ok(SysUserGroupVO.from(sysUserGroupService.getDetail(id)));
    }

    /** 创建用户组（含成员，事务一致）。 */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Long> create(@Valid @RequestBody UserGroupFormRequest req) {
        SysUserGroup group = toEntity(req);
        group.setMemberIds(req.getMemberIds());
        return R.ok(sysUserGroupService.create(group));
    }

    /** 更新用户组（业务标识不可变）。 */
    @PutMapping
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Void> update(@Valid @RequestBody UserGroupFormRequest req) {
        if (req.getId() == null) {
            throw new BaseException(
                    CommonErrorCode.PARAM_ERROR, "用户组 ID 不能为空");
        }
        sysUserGroupService.update(toEntity(req));
        return R.ok();
    }

    /** 停用（保留配置与成员）。 */
    @PutMapping("/{id:\\d+}/disable")
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Void> disable(@PathVariable Long id) {
        sysUserGroupService.disable(id);
        return R.ok();
    }

    /** 启用。 */
    @PutMapping("/{id:\\d+}/enable")
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Void> enable(@PathVariable Long id) {
        sysUserGroupService.enable(id);
        return R.ok();
    }

    /** 逻辑删除（连同成员关系）。 */
    @DeleteMapping("/{id:\\d+}")
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Void> delete(@PathVariable Long id) {
        sysUserGroupService.delete(id);
        return R.ok();
    }

    /** 成员 ID 列表（详情回填）。 */
    @GetMapping("/{id:\\d+}/members")
    @PreAuthorize("@ss.hasPermi('system:userGroup:list')")
    public R<List<Long>> members(@PathVariable Long id) {
        return R.ok(sysUserGroupService.listMemberIds(id));
    }

    /** 整量替换成员（空数组=清空）。 */
    @PutMapping("/{id:\\d+}/members")
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Void> updateMembers(@PathVariable Long id, @RequestBody List<Long> userIds) {
        sysUserGroupService.updateMemberIds(id, userIds);
        return R.ok();
    }

    /** 追加成员。 */
    @PostMapping("/{id:\\d+}/members")
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Void> addMembers(@PathVariable Long id, @RequestBody List<Long> userIds) {
        sysUserGroupService.addMemberIds(id, userIds);
        return R.ok();
    }

    /** 移除成员。 */
    @DeleteMapping("/{id:\\d+}/members")
    @PreAuthorize("@ss.hasPermi('system:userGroup:manage')")
    public R<Void> removeMembers(@PathVariable Long id, @RequestBody List<Long> userIds) {
        sysUserGroupService.removeMemberIds(id, userIds);
        return R.ok();
    }

    /** 成员候选用户分页（仅启用用户 + 数据范围）。 */
    @GetMapping("/candidates")
    @PreAuthorize("@ss.hasPermi('system:userGroup:list')")
    public R<PageResult<SysUserVO>> candidates(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "20") long pageSize,
                                             @RequestParam(required = false) String keyword) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(PageResult.map(sysUserGroupService.memberCandidates(pageParam, keyword),
                SysUserVO::from));
    }

    /** UserGroupFormRequest → SysUserGroup 转换。 */
    private SysUserGroup toEntity(UserGroupFormRequest req) {
        SysUserGroup group = new SysUserGroup();
        group.setId(req.getId());
        group.setGroupCode(req.getGroupCode());
        group.setGroupName(req.getGroupName());
        group.setStatus(req.getStatus());
        group.setRemark(req.getRemark());
        return group;
    }
}
