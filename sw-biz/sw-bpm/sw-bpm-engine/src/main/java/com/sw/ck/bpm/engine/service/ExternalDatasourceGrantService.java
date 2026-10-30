package com.sw.ck.bpm.engine.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.entity.ExternalDatasourceGrant;
import com.sw.ck.bpm.engine.mapper.ExternalDatasourceGrantMapper;
import com.sw.ck.bpm.engine.mapper.ExternalDatasourceMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部数据源引用授权服务（F020，direction-020 授权语义）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>授权主体维护：数据源管理员按用户/角色授予、撤销引用权（整集合替换）。</li>
 *   <li>引用校验：数据源管理员旁路；其余主体按 USER ID / ROLE 编码匹配授权行。</li>
 *   <li>可引用清单：当前主体可引用的数据源 ID 集合，供设计器可选项过滤。</li>
 * </ul>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>授权只约束设计期引用；已发布表单的运行期查询不回查授权。</li>
 *   <li>数据源必须属于当前租户且未删除，跨租户数据源一律视为不存在。</li>
 *   <li>替换采用复活语义：同键软删行重新启用，避免唯一索引与软删历史冲突。</li>
 * </ul>
 */
@Service
public class ExternalDatasourceGrantService {

    /** 单数据源授权主体数量上限，约束请求规模 */
    private static final int MAX_GRANTS_PER_DATASOURCE = 200;

    private static final Set<String> SUBJECT_TYPES = Set.of(
            ExternalDatasourceGrant.SUBJECT_ROLE, ExternalDatasourceGrant.SUBJECT_USER);

    private final ExternalDatasourceGrantMapper grantMapper;
    private final ExternalDatasourceMapper datasourceMapper;
    private final PermissionService permissionService;

    public ExternalDatasourceGrantService(ExternalDatasourceGrantMapper grantMapper,
                                          ExternalDatasourceMapper datasourceMapper,
                                          PermissionService permissionService) {
        this.grantMapper = grantMapper;
        this.datasourceMapper = datasourceMapper;
        this.permissionService = permissionService;
    }

    /** 当前登录主体是否为数据源管理员（管理员旁路授权匹配）。 */
    public boolean isDatasourceManager() {
        return permissionService.hasPermi("workflow:datasource:manage");
    }

    /** 指定数据源的授权主体清单（仅当前租户、未删除）。 */
    public List<ExternalDatasourceGrant> listGrants(Long datasourceId) {
        return grantMapper.selectList(Wrappers.lambdaQuery(ExternalDatasourceGrant.class)
                .eq(ExternalDatasourceGrant::getDatasourceId, datasourceId)
                .orderByAsc(ExternalDatasourceGrant::getSubjectType)
                .orderByAsc(ExternalDatasourceGrant::getSubjectKey));
    }

    /**
     * 整集合替换数据源授权主体（空集合 = 全部撤销）。
     * 同键软删行复活为有效授权，被移除的行软删保留历史。
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceGrants(Long datasourceId, List<GrantItem> items) {
        requireExistingDatasource(datasourceId);
        List<GrantItem> target = normalize(items);
        Map<String, ExternalDatasourceGrant> existing = new LinkedHashMap<>();
        for (ExternalDatasourceGrant row : grantMapper.selectAllIncludingDeleted(datasourceId)) {
            existing.put(row.getSubjectType() + ":" + row.getSubjectKey(), row);
        }

        Set<String> targetKeys = new LinkedHashSet<>();
        for (GrantItem item : target) {
            String key = item.subjectType() + ":" + item.subjectKey();
            targetKeys.add(key);
            ExternalDatasourceGrant row = existing.get(key);
            if (row == null) {
                insertGrant(datasourceId, item);
            } else if (row.getDeleted() != null && row.getDeleted() == 1) {
                grantMapper.reviveById(row.getId());
            }
        }
        for (Map.Entry<String, ExternalDatasourceGrant> entry : existing.entrySet()) {
            if (!targetKeys.contains(entry.getKey())
                    && (entry.getValue().getDeleted() == null || entry.getValue().getDeleted() == 0)) {
                grantMapper.deleteById(entry.getValue().getId());
            }
        }
    }

    /** 当前登录主体是否允许引用指定数据源（管理员旁路或授权匹配）。 */
    public boolean isReferenceAllowed(Long datasourceId) {
        if (datasourceId == null) {
            return false;
        }
        if (isDatasourceManager()) {
            return datasourceExists(datasourceId);
        }
        LoginUser user = LoginUserHolder.get();
        if (user == null) {
            return false;
        }
        return !matchGrants(datasourceId, user.getUserId(), user.getRoles()).isEmpty()
                && datasourceExists(datasourceId);
    }

    /** 当前登录主体可引用的数据源 ID 集合；管理员返回本租户全部数据源。 */
    public Set<Long> referenceAllowedDatasourceIds() {
        if (isDatasourceManager()) {
            Set<Long> ids = new LinkedHashSet<>();
            for (ExternalDatasource row : datasourceMapper.selectList(Wrappers.lambdaQuery(ExternalDatasource.class))) {
                ids.add(row.getId());
            }
            return ids;
        }
        LoginUser user = LoginUserHolder.get();
        if (user == null) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (ExternalDatasourceGrant row : matchAllGrants(user.getUserId(), user.getRoles())) {
            ids.add(row.getDatasourceId());
        }
        return ids;
    }

    // ==================== 内部 ====================

    private void insertGrant(Long datasourceId, GrantItem item) {
        ExternalDatasourceGrant grant = new ExternalDatasourceGrant();
        grant.setDatasourceId(datasourceId);
        grant.setSubjectType(item.subjectType());
        grant.setSubjectKey(item.subjectKey());
        grantMapper.insert(grant);
    }

    private List<ExternalDatasourceGrant> matchGrants(Long datasourceId, Long userId, List<String> roles) {
        List<ExternalDatasourceGrant> matched = new ArrayList<>();
        for (ExternalDatasourceGrant row : matchAllGrants(userId, roles)) {
            if (row.getDatasourceId().equals(datasourceId)) {
                matched.add(row);
            }
        }
        return matched;
    }

    private List<ExternalDatasourceGrant> matchAllGrants(Long userId, List<String> roles) {
        List<ExternalDatasourceGrant> rows = grantMapper.selectList(
                Wrappers.lambdaQuery(ExternalDatasourceGrant.class));
        String userIdKey = String.valueOf(userId);
        List<ExternalDatasourceGrant> matched = new ArrayList<>();
        for (ExternalDatasourceGrant row : rows) {
            if (ExternalDatasourceGrant.SUBJECT_USER.equals(row.getSubjectType())
                    && userIdKey.equals(row.getSubjectKey())) {
                matched.add(row);
            } else if (ExternalDatasourceGrant.SUBJECT_ROLE.equals(row.getSubjectType())
                    && roles != null && roles.contains(row.getSubjectKey())) {
                matched.add(row);
            }
        }
        return matched;
    }

    private boolean datasourceExists(Long datasourceId) {
        return datasourceMapper.selectById(datasourceId) != null;
    }

    private void requireExistingDatasource(Long datasourceId) {
        if (datasourceId == null || datasourceMapper.selectById(datasourceId) == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "外部数据源不存在: id=" + datasourceId);
        }
    }

    private List<GrantItem> normalize(List<GrantItem> items) {
        List<GrantItem> normalized = new ArrayList<>();
        if (items == null) {
            return normalized;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (GrantItem item : items) {
            if (item == null) {
                continue;
            }
            String type = item.subjectType() == null ? "" : item.subjectType().trim().toUpperCase();
            String key = item.subjectKey() == null ? "" : item.subjectKey().trim();
            if (!SUBJECT_TYPES.contains(type)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "授权主体类型非法: " + item.subjectType());
            }
            if (ExternalDatasourceGrant.SUBJECT_USER.equals(type) && !key.matches("[0-9]{1,19}")) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "用户主体必须为数字 ID");
            }
            if (ExternalDatasourceGrant.SUBJECT_ROLE.equals(type) && !key.matches("[a-zA-Z0-9_-]{1,64}")) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "角色编码非法");
            }
            if (!seen.add(type + ":" + key)) {
                continue;
            }
            normalized.add(new GrantItem(type, key));
        }
        if (normalized.size() > MAX_GRANTS_PER_DATASOURCE) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "单数据源授权主体数超过上限: " + MAX_GRANTS_PER_DATASOURCE);
        }
        return normalized;
    }

    /** 授权主体请求项。 */
    public record GrantItem(String subjectType, String subjectKey) {
    }
}
