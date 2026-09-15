package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.UserPageQuery;
import com.sw.ck.system.service.UserPostAssociation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * 用户管理控制器。
 */
@RestController
@RequestMapping("/system/user")
public class UserController {

    private final SysUserService sysUserService;

    public UserController(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    /** 内嵌 DTO：用户表单（含明文密码） */
    @Data
    public static class UserFormRequest {
        private Long id;
        @NotBlank(message = "用户名不能为空")
        private String username;
        private String realName;
        private String email;
        private String phone;
        private Integer sex;
        private Integer status;
        private Long deptId;
        private List<Long> roleIds;
        /** 岗位任职（岗位在部门内承担；deptId 缺省回落用户主部门） */
        private List<PostAssignment> posts;
        /** 明文密码 — 新建时必填，更新时为空表示不修改 */
        private String plainPassword;
    }

    /** 内嵌 DTO：岗位任职（postId + 可选任职部门 deptId） */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PostAssignment {
        private Long postId;
        private Long deptId;
    }

    /**
     * 分页查询用户。
     */
    @PostMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public R<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestBody(required = false) UserPageQuery query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(query == null ? sysUserService.page(pageParam) : sysUserService.page(pageParam, query));
    }

    /**
     * 获取用户详情。
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public R<SysUser> get(@PathVariable Long id) {
        return R.ok(sysUserService.getById(id));
    }

    /**
     * 创建用户。
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:user:create')")
    @Transactional(rollbackFor = Exception.class)
    public R<Long> create(@Valid @RequestBody UserFormRequest req) {
        SysUser user = toEntity(req);
        Long id = sysUserService.createWithAssociations(user, req.getPlainPassword(), req.getRoleIds(), toPostAssociations(req.getPosts()));
        return R.ok(id);
    }

    /**
     * 更新用户。
     */
    @PutMapping
    @PreAuthorize("@ss.hasPermi('system:user:update')")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> update(@Valid @RequestBody UserFormRequest req) {
        SysUser user = toEntity(req);
        sysUserService.updateWithAssociations(user, req.getPlainPassword(), req.getRoleIds(), toPostAssociations(req.getPosts()));
        return R.ok();
    }

    /**
     * 删除用户（逻辑删除）。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:user:delete')")
    public R<Void> delete(@PathVariable Long id) {
        sysUserService.delete(id);
        return R.ok();
    }

    @GetMapping("/{id}/roles")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public R<List<Long>> roles(@PathVariable Long id) {
        return R.ok(sysUserService.listRoleIds(id));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("@ss.hasPermi('system:user:update')")
    public R<Void> updateRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        sysUserService.updateRoleIds(id, roleIds);
        return R.ok();
    }

    @GetMapping("/{id}/posts")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public R<List<PostAssignment>> posts(@PathVariable Long id) {
        return R.ok(sysUserService.listPosts(id).stream()
                .map(row -> new PostAssignment(row.getPostId(), row.getDeptId()))
                .toList());
    }

    @PutMapping("/{id}/posts")
    @PreAuthorize("@ss.hasPermi('system:user:update')")
    public R<Void> updatePosts(@PathVariable Long id, @RequestBody List<PostAssignment> posts) {
        sysUserService.updatePosts(id, toPostAssociations(posts)); return R.ok();
    }

    /** UserFormRequest → SysUser 转换 */
    private SysUser toEntity(UserFormRequest req) {
        SysUser user = new SysUser();
        user.setId(req.getId());
        user.setUsername(req.getUsername());
        user.setRealName(req.getRealName());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setSex(req.getSex());
        user.setStatus(req.getStatus());
        user.setDeptId(req.getDeptId());
        user.setRoleIds(req.getRoleIds());
        return user;
    }

    private List<UserPostAssociation> toPostAssociations(List<PostAssignment> posts) {
        if (posts == null) {
            return null;
        }
        return posts.stream()
                .map(item -> new UserPostAssociation(item.getPostId(), item.getDeptId()))
                .toList();
    }
}
