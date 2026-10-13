package com.sw.ck.system.api.dept;

import java.io.Serializable;

/**
 * BF20261013-021：部门目录行（流程脚本组织函数快照用；不含敏感字段）。
 *
 * @param deptId   部门 ID（正常状态、同租户）
 * @param parentId 上级部门 ID（根部门为 0）
 * @param leaderId 有效负责人用户 ID（负责人启用且同租户才登记；未设置/无效为 null）
 */
public record DeptDirectoryRow(Long deptId, Long parentId, Long leaderId) implements Serializable {
}
