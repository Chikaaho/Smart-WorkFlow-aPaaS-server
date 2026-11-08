package com.sw.ck.common.datascope;

import com.sw.ck.common.security.LoginContextProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据范围 → 参数化过滤条件（用于无 {@code dept_id} 列的业务表）。
 * <p>
 * {@code sw_bpm_instance} / {@code sw_job_info} / {@code sw_job_log} /
 * {@code sw_storage_file} / {@code sw_agent_graph_execution} /
 * {@code sw_agent_model_config} 这六张表只有归属用户列（{@code create_by} /
 * {@code initiator_id}）、没有 {@code dept_id} 列，无法被
 * {@link com.sw.ck.common.config.mybatis.datascope.DataScopeHandler} 直接纳管——
 * handler 的部门三档固定拼接 {@code dept_id IN (...)}、SELF 档固定拼接
 * {@code create_by = userId}，都不适用于这些表。对应地，这些表的自定义 Mapper 查询方法
 * 以等效条件实现数据范围过滤：
 * <pre>
 *   SELF        → 归属用户列 = #{userId}
 *   DEPT 家族   → 归属用户列 IN (SELECT id FROM sys_user WHERE dept_id IN (...))
 *   空集合      → 1 = 0（恒假，对齐 handler 的 noRows() 语义）
 * </pre>
 * 本类把"当前登录人数据范围 → 过滤条件"的解析集中一处，语义与
 * {@code DataScopeHandler#getSqlSegment} 逐档对齐（超管短路 / ALL 放行 / SELF 取不到
 * userId 恒假 / CUSTOM 空集恒假），scope ids 取自既有 SPI（{@link LoginContextProvider} /
 * {@link DeptScopeProvider}），各业务 Service 注入这两个 SPI 后统一调用
 * {@link #resolve(LoginContextProvider, DeptScopeProvider)}，避免六处重复实现。
 * <p>
 * Mapper SQL 侧按字段语义消费：{@code userId} 非空 → 等值条件；{@code deptIds} 非空 →
 * 子查询条件；{@code deptIds} 为空列表 / {@code alwaysFalse} → 恒假；两者皆空 → 不限制。
 * </p>
 */
public final class DataScopeFilter {

    /** SELF 档：归属用户列 = userId。仅 SELF 档非空。 */
    private final Long userId;

    /**
     * DEPT / DEPT_AND_CHILD / CUSTOM 档：归属用户列 IN (SELECT id FROM sys_user WHERE
     * dept_id IN deptIds)。null=不限制；空列表=恒假（对齐 handler 空集 noRows 语义）。
     */
    private final List<Long> deptIds;

    /** SELF 档且取不到 userId（无登录态）→ 恒假，对齐 handler 的 buildSelfCondition。 */
    private final boolean alwaysFalse;

    private DataScopeFilter(Long userId, List<Long> deptIds, boolean alwaysFalse) {
        this.userId = userId;
        this.deptIds = deptIds;
        this.alwaysFalse = alwaysFalse;
    }

    /** 不限制：ALL 档 / 超管短路 / 无登录态默认。 */
    public static DataScopeFilter none() {
        return new DataScopeFilter(null, null, false);
    }

    /** SELF 档。userId 为 null（无登录态）时等价恒假。 */
    public static DataScopeFilter self(Long userId) {
        return new DataScopeFilter(userId, null, userId == null);
    }

    /** DEPT 家族档。空列表（含 null 入参）等价恒假。 */
    public static DataScopeFilter depts(List<Long> deptIds) {
        return new DataScopeFilter(null, deptIds == null ? List.of() : deptIds, false);
    }

    /**
     * 解析当前登录人的数据范围为参数化过滤条件，语义与
     * {@code DataScopeHandler#getSqlSegment} 逐档对应：
     * <ul>
     *   <li>超管短路 → {@link #none()}；</li>
     *   <li>scopeType 为 null 或 ALL → {@link #none()}；</li>
     *   <li>SELF → {@link #self(userId)}（userId 取不到 → 恒假）；</li>
     *   <li>DEPT → 本部门单元素（deptId 取不到 → 恒假）；</li>
     *   <li>DEPT_AND_CHILD → 本部门 + {@link DeptScopeProvider#listChildDeptIds} 下级；</li>
     *   <li>CUSTOM → {@link LoginContextProvider#getCustomDeptIds()} 并集（空 → 恒假）。</li>
     * </ul>
     */
    public static DataScopeFilter resolve(LoginContextProvider loginContextProvider,
                                          DeptScopeProvider deptScopeProvider) {
        if (loginContextProvider.isSuperAdmin()) {
            return none();
        }
        DataScopeType scopeType = loginContextProvider.getDataScopeType();
        if (scopeType == null || scopeType == DataScopeType.ALL) {
            return none();
        }
        return switch (scopeType) {
            case SELF -> self(loginContextProvider.getUserId());
            case DEPT -> {
                Long deptId = loginContextProvider.getDeptId();
                yield depts(deptId == null ? List.of() : List.of(deptId));
            }
            case DEPT_AND_CHILD -> {
                Long deptId = loginContextProvider.getDeptId();
                if (deptId == null) {
                    yield depts(List.of());
                }
                List<Long> ids = new ArrayList<>();
                ids.add(deptId);
                ids.addAll(deptScopeProvider.listChildDeptIds(deptId));
                yield depts(ids);
            }
            case CUSTOM -> {
                Set<Long> customDeptIds = loginContextProvider.getCustomDeptIds();
                yield depts(customDeptIds == null || customDeptIds.isEmpty()
                        ? List.of()
                        : new ArrayList<>(customDeptIds));
            }
            default -> none();
        };
    }

    public Long getUserId() {
        return userId;
    }

    /**
     * 归属用户列是 VARCHAR 时使用的字符串形态；避免 SQL 里写 {@code CAST(... AS VARCHAR)}，
     * 该类型名在 MySQL/Oracle/SQL Server 上不一致。
     */
    public String getUserIdText() {
        return userId == null ? null : userId.toString();
    }

    public List<Long> getDeptIds() {
        return deptIds;
    }

    public boolean isAlwaysFalse() {
        return alwaysFalse;
    }
}
