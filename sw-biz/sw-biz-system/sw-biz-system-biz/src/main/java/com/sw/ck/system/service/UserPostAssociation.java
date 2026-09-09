package com.sw.ck.system.service;

/**
 * 用户-岗位任职关联（I1 组织与权限底座）。
 * <p>
 * 岗位在部门内承担：{@code deptId} 为任职部门；为 null 时服务端回落为用户
 * 当前主部门。岗位不是角色，本关联不携带任何菜单/接口权限语义。
 * </p>
 */
public class UserPostAssociation {

    /** 岗位 ID（必填） */
    private Long postId;

    /** 任职部门 ID（可空，null 表示跟随用户主部门） */
    private Long deptId;

    public UserPostAssociation() {
    }

    public UserPostAssociation(Long postId, Long deptId) {
        this.postId = postId;
        this.deptId = deptId;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }
}
