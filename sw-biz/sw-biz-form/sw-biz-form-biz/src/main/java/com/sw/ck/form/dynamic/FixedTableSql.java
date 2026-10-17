package com.sw.ck.form.dynamic;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 固定系统表只读 SQL 的受控入口（模块内唯一允许直接持有 JdbcTemplate 的包，见 G1 守门）。
 *
 * <p>本类只承载<b>固定表</b>（非动态宽表）的只读查询：动态宽表一律经 {@link DynamicTableSql}，
 * 固定表查询集中在本类，避免 {@code jdbcTemplate} 调用散落到业务包。约定与动态入口同向：</p>
 * <ol>
 *   <li>表名与列名为固定字面量，不接受外部输入拼接；</li>
 *   <li>参数一律绑定（占位符），任何值拼接不得出现；</li>
 *   <li>带逻辑删除的表必须显式 {@code deleted = 0}；</li>
 *   <li>只读：本类不提供任何写入口。</li>
 * </ol>
 */
public final class FixedTableSql {

    private FixedTableSql() {
    }

    /** 启用租户选项（0.1.7 #1 可见范围 TENANT 维度候选集）：id + 名称，按 id 升序。 */
    public static List<Map<String, Object>> listEnabledTenantOptions(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList(
                "SELECT id AS \"id\", name AS \"name\" FROM sys_tenant "
                        + "WHERE deleted = 0 ORDER BY id");
    }

    /** 用户岗位 ID 集合（0.1.7 #1 可见范围 POST 维度判定依据），字符串化由调用方完成。 */
    public static List<Long> loadUserPostIds(JdbcTemplate jdbcTemplate, Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT post_id FROM sys_user_post WHERE user_id = ? AND deleted = 0",
                Long.class, userId);
    }
}
