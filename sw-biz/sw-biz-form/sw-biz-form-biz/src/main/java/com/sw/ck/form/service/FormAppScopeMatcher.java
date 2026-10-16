package com.sw.ck.form.service;

import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.entity.FormAppScopeEntity;
import com.sw.ck.security.holder.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用可见范围多维度判定（0.1.7 #1）。
 * <p>
 * 管理端列表/用户端列表/访问闸门共用同一判定，保证「配置口径 = 判定口径」：
 * ALL 直接可见；SPECIFIED 任一维度（USER/DEPT/ROLE/POST/TENANT）命中即可见，
 * 维度集合全空 = 无人可见（fail-closed）。岗位维度经 {@code sys_user_post} 关联查询；
 * 查询失败按未命中处理，不放行。
 * </p>
 */
@Service
public class FormAppScopeMatcher {

    private static final Logger log = LoggerFactory.getLogger(FormAppScopeMatcher.class);

    private static final String SCOPE_ALL = "ALL";

    private final JdbcTemplate jdbcTemplate;

    public FormAppScopeMatcher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 判定应用对当前用户的可见性（维度命中任一即可）。 */
    public boolean matches(FormAppEntity app, LoginUser user, List<FormAppScopeEntity> rows) {
        if (app == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (SCOPE_ALL.equals(app.getVisibleScopeType())) {
            return true;
        }
        if (rows == null || rows.isEmpty()) {
            // SPECIFIED：维度集合空 = 无人可见
            return false;
        }
        Set<String> postIds = null;
        for (FormAppScopeEntity row : rows) {
            String scopeId = row.getScopeId();
            if (scopeId == null) {
                continue;
            }
            Boolean hit = switch (row.getScopeType()) {
                case FormAppScopeTypes.USER -> String.valueOf(user.getUserId()).equals(scopeId);
                case FormAppScopeTypes.DEPT -> user.getDeptId() != null
                        && String.valueOf(user.getDeptId()).equals(scopeId);
                case FormAppScopeTypes.ROLE -> user.getRoles() != null
                        && user.getRoles().contains(scopeId);
                case FormAppScopeTypes.TENANT -> user.getTenantId() != null
                        && String.valueOf(user.getTenantId()).equals(scopeId);
                case FormAppScopeTypes.POST -> {
                    if (postIds == null) {
                        postIds = loadPostIds(user.getUserId());
                    }
                    yield postIds.contains(scopeId);
                }
                default -> false;
            };
            if (Boolean.TRUE.equals(hit)) {
                return true;
            }
        }
        return false;
    }

    /** 用户岗位 ID 集合（字符串化；查询失败返回空集并告警，不放行 POST 维度）。 */
    public Set<String> loadPostIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        try {
            return jdbcTemplate.queryForList(
                    "SELECT post_id FROM sys_user_post WHERE user_id = ? AND deleted = 0",
                    Long.class, userId).stream()
                    .map(String::valueOf)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Load user post ids failed: userId={}", userId, e);
            return Set.of();
        }
    }
}
