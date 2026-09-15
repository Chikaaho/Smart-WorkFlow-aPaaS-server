package com.sw.ck.form.service;

import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;

/**
 * 表单记录数据范围解析（I2，方向 §4.6）。
 *
 * <p>复用 I1 数据范围权威：超管短路；ALL/空 → 不限制；SELF → create_by 等值；
 * DEPT/DEPT_AND_CHILD/CUSTOM → create_by IN (SELECT id FROM sys_user WHERE dept_id IN (...))。
 * 列表、详情、更新、删除、导入导出共用本解析，调用方不得选择是否启用安全过滤：
 * 服务缺失时按登录态降级解析（DEPT_AND_CHILD 无法展开下级时退化为本部门）。</p>
 */
public final class FormDataScopeSupport {

    private FormDataScopeSupport() {
    }

    /**
     * 从登录态解析当前数据范围过滤器（服务端权威，无法关闭）。
     */
    public static DataScopeFilter resolve(LoginUser user,
                                          LoginContextProvider loginContextProvider,
                                          DeptScopeProvider deptScopeProvider) {
        if (user == null) {
            return DataScopeFilter.none();
        }
        if (user.isSuperAdmin()) {
            return DataScopeFilter.none();
        }
        com.sw.ck.security.holder.DataScope type = user.getDataScope();
        if (type == null || type == com.sw.ck.security.holder.DataScope.ALL) {
            return DataScopeFilter.none();
        }
        return switch (type) {
            case SELF -> DataScopeFilter.self(user.getUserId());
            case DEPT -> DataScopeFilter.depts(user.getDeptId() == null
                    ? java.util.List.of() : java.util.List.of(user.getDeptId()));
            case DEPT_AND_CHILD -> {
                if (loginContextProvider != null && deptScopeProvider != null
                        && user.getDeptId() != null) {
                    java.util.List<Long> ids = new java.util.ArrayList<>();
                    ids.add(user.getDeptId());
                    ids.addAll(deptScopeProvider.listChildDeptIds(user.getDeptId()));
                    yield DataScopeFilter.depts(ids);
                }
                // Provider 缺失时退化为本部门（fail-closed，不放大可见范围）
                yield DataScopeFilter.depts(user.getDeptId() == null
                        ? java.util.List.of() : java.util.List.of(user.getDeptId()));
            }
            case CUSTOM -> DataScopeFilter.depts(user.getCustomDeptIds() == null
                    ? java.util.List.of() : new java.util.ArrayList<>(user.getCustomDeptIds()));
            default -> DataScopeFilter.none();
        };
    }

    /**
     * 把数据范围条件追加到 WHERE（create_by 归属语义，参数化绑定）。
     */
    public static void appendWhere(StringBuilder whereBuilder, java.util.List<Object> params,
                                   DataScopeFilter scopeFilter) {
        if (scopeFilter == null) {
            return;
        }
        if (scopeFilter.isAlwaysFalse()) {
            whereBuilder.append(" AND 1 = 0");
        } else if (scopeFilter.getUserId() != null) {
            whereBuilder.append(" AND \"create_by\" = ?");
            params.add(scopeFilter.getUserId());
        } else if (scopeFilter.getDeptIds() != null) {
            java.util.List<Long> deptIds = scopeFilter.getDeptIds();
            if (deptIds.isEmpty()) {
                whereBuilder.append(" AND 1 = 0");
            } else {
                String placeholders = deptIds.stream().map(d -> "?")
                        .collect(java.util.stream.Collectors.joining(", "));
                whereBuilder.append(" AND \"create_by\" IN (SELECT id FROM sys_user WHERE dept_id IN (")
                        .append(placeholders).append("))");
                params.addAll(deptIds);
            }
        }
    }
}
