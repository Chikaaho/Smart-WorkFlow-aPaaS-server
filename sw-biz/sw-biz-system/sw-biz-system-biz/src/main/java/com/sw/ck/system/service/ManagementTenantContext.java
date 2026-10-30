package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.mapper.SysTenantMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 管理操作的操作者与目标租户分别校验；筛选不改写登录身份。 */
@Service
public class ManagementTenantContext {
    private final SysTenantMapper tenants;
    private final TenantValidityService validity;

    public ManagementTenantContext(SysTenantMapper tenants, TenantValidityService validity) {
        this.tenants = tenants;
        this.validity = validity;
    }

    public record Option(Long id, String name, String code, boolean writable) {}

    public Long createTarget(Long requested) {
        LoginUser actor = requireActor();
        Long target = requested;
        if (isRoot(actor)) {
            if (target == null) throw invalid("请先选择目标租户");
        } else {
            if (target != null && !target.equals(actor.getTenantId())) throw invalid("不能创建其他租户的对象");
            target = actor.getTenantId();
        }
        if (!validity.isValid(target)) throw invalid("目标租户当前不可用");
        return target;
    }

    public Long updateTarget(Long requested, Long owner) {
        if (owner == null) throw invalid("对象租户信息缺失");
        if (requested != null && !requested.equals(owner)) throw invalid("不能修改对象所属租户");
        return createTarget(owner);
    }

    public Long readTarget(Long requested) {
        LoginUser actor = requireActor();
        if (isRoot(actor)) return requested;
        if (requested != null && !requested.equals(actor.getTenantId())) throw invalid("不能查询其他租户的对象");
        return actor.getTenantId();
    }

    public Option current() {
        return option(requireTenant(requireActor().getTenantId()));
    }

    public List<Option> options(String keyword, boolean creatable) {
        if (!isRoot(requireActor())) throw invalid("仅系统超级管理员可以选择目标租户");
        String term = keyword == null ? "" : keyword.trim();
        if (term.length() > 64) throw invalid("租户搜索条件过长");
        try (var ignored = TenantLineSuspension.suspended()) {
            var query = Wrappers.<SysTenant>lambdaQuery()
                    .and(!term.isEmpty(), q -> q.like(SysTenant::getName, term).or().like(SysTenant::getCode, term));
            if (creatable) query.eq(SysTenant::getStatus, 0)
                    .and(q -> q.isNull(SysTenant::getExpireTime).or().ge(SysTenant::getExpireTime, LocalDateTime.now()));
            return tenants.selectList(query.orderByAsc(SysTenant::getId).last("LIMIT 200"))
                    .stream().map(this::option).toList();
        }
    }

    public Map<Long, String> names(Collection<Long> ids) {
        var distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        LoginUser actor = requireActor();
        if (!isRoot(actor) && distinct.stream().anyMatch(id -> !id.equals(actor.getTenantId())))
            throw invalid("对象租户与当前身份不符");
        try (var ignored = TenantLineSuspension.suspended()) {
            return tenants.selectBatchIds(distinct).stream()
                    .collect(Collectors.toMap(SysTenant::getId, SysTenant::getName));
        }
    }

    public List<com.sw.ck.system.entity.SysUser> decorateUsers(List<com.sw.ck.system.entity.SysUser> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        Map<Long, String> labels = names(rows.stream().map(com.sw.ck.system.entity.SysUser::getTenantId).toList());
        rows.forEach(row -> row.setTenantName(labels.get(row.getTenantId())));
        return rows;
    }

    public List<com.sw.ck.system.entity.SysDept> decorateDepartments(List<com.sw.ck.system.entity.SysDept> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        Map<Long, String> labels = names(rows.stream().map(com.sw.ck.system.entity.SysDept::getTenantId).toList());
        rows.forEach(row -> row.setTenantName(labels.get(row.getTenantId())));
        return rows;
    }

    private SysTenant requireTenant(Long id) {
        try (var ignored = TenantLineSuspension.suspended()) {
            SysTenant tenant = tenants.selectGlobalById(id);
            if (tenant == null) throw invalid("租户不存在或已删除");
            return tenant;
        }
    }

    private Option option(SysTenant tenant) {
        return new Option(tenant.getId(), tenant.getName(), tenant.getCode(),
                Integer.valueOf(0).equals(tenant.getStatus()) && (tenant.getExpireTime() == null
                        || !tenant.getExpireTime().isBefore(LocalDateTime.now())));
    }

    private static LoginUser requireActor() {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null || actor.getTenantId() == null) throw invalid("缺少登录租户上下文");
        return actor;
    }

    private static boolean isRoot(LoginUser actor) {
        return actor.isSuperAdmin() && Long.valueOf(0).equals(actor.getTenantId());
    }

    private static BaseException invalid(String message) {
        return new BaseException(CommonErrorCode.PARAM_ERROR, message);
    }
}
