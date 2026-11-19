package com.sw.ck.system.sso;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SsoAuthState;
import com.sw.ck.system.entity.SsoUserBinding;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SsoUserBindingMapper;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.service.RefreshTokenService;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.TenantValidityService;
import com.sw.ck.system.vo.SsoBindingItemVO;
import com.sw.ck.system.vo.SsoBindingListVO;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 外部身份与本地账号的绑定域：回调准入（B 端手机号准入自动绑定/已绑定登录）、
 * 手动绑定/解绑、绑定列表。自 SsoAuthService 拆出（行为不变）；事务边界仍由
 * 门面 {@code SsoAuthService} 的 {@code @Transactional} 方法承担。
 */
@Service
class SsoUserBindingService {

    @Autowired
    private SsoUserBindingMapper bindingMapper;
    @Autowired
    private SysUserService sysUserService;
    @Autowired
    private TenantValidityService tenantValidityService;
    @Autowired
    private SsoAuditRecorder auditRecorder;
    @Autowired(required = false)
    private LoginUserCacheService loginUserCacheService;
    @Autowired(required = false)
    private RefreshTokenService refreshTokenService;

    /**
     * 查询本地账号的全部有效绑定（个人中心展示；不回传外部原文，只回 Provider 与摘要前 8 位）。
     */
    SsoBindingListVO listBindings(Long userId) {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失");
        }
        var bindings = bindingMapper.selectList(
                Wrappers.<SsoUserBinding>lambdaQuery()
                        .eq(SsoUserBinding::getUserId, userId)
                        .eq(SsoUserBinding::getBindStatus, SsoBindStatusEnum.ACTIVE.getCode()));
        List<SsoBindingItemVO> view = bindings.stream()
                .map(b -> {
                    SsoBindingItemVO item = new SsoBindingItemVO();
                    item.setProvider(b.getProvider());
                    item.setExternalDigestPrefix(b.getExternalDigest().substring(0, 8));
                    return item;
                })
                .toList();
        SsoBindingListVO result = new SsoBindingListVO();
        result.setBindings(view);
        return result;
    }

    /**
     * B 端手机号准入（sso-admin-config 方向 §一）：SSO 只识别并绑定当前租户组织
     * 架构中已存在的有效用户，个人/企业身份模式都不能绕过本地用户准入。
     * <ol>
     *   <li>已有绑定：装载本地用户并校验有效性（存在/同租户/未停用）；厂商可信
     *       手机号缺失或与本地账号手机号规范化后不一致 → 拒绝（不自动迁移/合并）；</li>
     *   <li>无绑定：按规范化可信手机号在当前租户查唯一有效用户；唯一命中且不与
     *       既有外部绑定冲突 → 安全建立绑定并签发既有本地会话；</li>
     *   <li>无用户/重复手机号/账号失效/缺可信手机号/冲突 → 统一对外安全结论
     *       （{@link SystemErrorKeys#SSO_ADMISSION_REJECTED}），脱敏原因进审计。</li>
     * </ol>
     * 手机号原文与规范化值不进审计/日志/响应；审计 detail 仅记脱敏原因类别。
     */
    SsoAuthService.CallbackResult admitCallback(String provider, SsoAuthState stateRow,
                                                String externalId, String vendorMobile, String configDigest) {
        Long tenantId = stateRow.getTenantId();
        String externalDigest = SsoAuthService.sha256(externalId);
        String normalizedPhone = SsoPhoneNormalizer.normalize(vendorMobile);
        // 摘要即权威：external_id 列存摘要（明文不落 SQL/日志，I5 复验 G7b）
        SsoUserBinding binding = bindingMapper.selectActiveByExternal(provider, tenantId, externalDigest);
        if (binding != null) {
            LocalUserView local = loadLocalUser(binding.getUserId());
            if (local == null || !tenantId.equals(local.tenantId()) || !local.active()) {
                auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, binding.getUserId(),
                        externalDigest, "bound local user unavailable", tenantId);
                throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG);
            }
            String localPhone = SsoPhoneNormalizer.normalize(local.phone());
            if (normalizedPhone == null || localPhone == null || !localPhone.equals(normalizedPhone)) {
                // 厂商或本地手机号变化：不自动迁移绑定，交管理员核对
                auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, binding.getUserId(),
                        externalDigest, normalizedPhone == null ? "trusted phone missing"
                                : "phone changed (vendor vs local)", tenantId);
                throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG);
            }
            auditRecorder.audit(provider, "LOGIN_SUCCESS", SysAuditLog.RESULT_SUCCESS, null, binding.getUserId(), externalDigest,
                    null, tenantId);
            return new SsoAuthService.CallbackResult(provider, tenantId, externalId, externalDigest,
                    binding.getUserId(), stateRow.getRedirectPath(), true, configDigest);
        }
        // 未绑定：按可信手机号在当前租户准入唯一有效用户
        if (normalizedPhone == null) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, externalDigest,
                    "trusted phone missing", tenantId);
            throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG);
        }
        LocalUserView target = findUniqueTenantUserByPhone(tenantId, normalizedPhone);
        if (target == null) {
            // 零命中与多命中在审计中区分；对外同一结论
            long matches = countTenantUsersByPhone(tenantId, normalizedPhone);
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, externalDigest,
                    matches > 1 ? "ambiguous phone in tenant" : "no local user with trusted phone", tenantId);
            throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG);
        }
        // 目标用户已绑定该 Provider 的其他外部主体：稳定主体与既有手机号绑定冲突，
        // 禁止仅凭手机号覆盖外部身份
        SsoUserBinding userBound = bindingMapper.selectActiveByUser(provider, tenantId, target.userId());
        if (userBound != null) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, target.userId(),
                    externalDigest, "user already bound to another external id", tenantId);
            throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG);
        }
        // 跨租户稳定主体冲突（V87 全局唯一语义）：同一 (provider, 摘要) 全局仅一个租户可绑定
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            Long crossTenant = bindingMapper.selectCount(
                    Wrappers.<SsoUserBinding>lambdaQuery()
                            .eq(SsoUserBinding::getProvider, provider)
                            .eq(SsoUserBinding::getExternalDigest, externalDigest)
                            .eq(SsoUserBinding::getBindStatus, SsoBindStatusEnum.ACTIVE.getCode())
                            .ne(SsoUserBinding::getTenantId, tenantId));
            if (crossTenant != null && crossTenant > 0) {
                auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, target.userId(),
                        externalDigest, "external subject already bound in another tenant", tenantId);
                throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG);
            }
        }
        // 解绑（UNBOUND）行仍占用 (provider,tenant,external)/(provider,tenant,user) 两个唯一键
        // （V84 唯一索引含 deleted，仅 0/1 两值——逻辑删除第二个循环必撞键）。重绑前物理删除
        // 占位行（绑定行历史在 sys_sso_audit_record；@TableLogic 逻辑删除不可用于此）。
        // 外部主体的占位行属于其他本地账号时仍拒绝（不自动迁移，交管理员核对）。
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            SsoUserBinding dormantBySubject = bindingMapper.selectOne(
                    Wrappers.<SsoUserBinding>lambdaQuery()
                            .eq(SsoUserBinding::getProvider, provider)
                            .eq(SsoUserBinding::getTenantId, tenantId)
                            .eq(SsoUserBinding::getExternalDigest, externalDigest)
                            .eq(SsoUserBinding::getBindStatus, SsoBindStatusEnum.UNBOUND.getCode())
                            .orderByDesc(SsoUserBinding::getId)
                            .last("LIMIT 1"));
            if (dormantBySubject != null && !target.userId().equals(dormantBySubject.getUserId())) {
                auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, target.userId(),
                        externalDigest, "dormant binding belongs to another local user", tenantId);
                throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG);
            }
            bindingMapper.deleteDormantUnboundRows(provider, tenantId, externalDigest, target.userId());
        }
        SsoUserBinding created = new SsoUserBinding();
        created.setProvider(provider);
        created.setTenantId(tenantId);
        created.setExternalId(externalDigest);
        created.setExternalDigest(externalDigest);
        created.setUserId(target.userId());
        created.setBindStatus(SsoBindStatusEnum.ACTIVE.getCode());
        try {
            bindingMapper.insert(created);
        } catch (DuplicateKeyException e) {
            // 并发绑定以数据库全局唯一键为最终仲裁（fail closed，无会话签发）
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.ADMISSION_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, target.userId(),
                    externalDigest, "binding unique constraint rejected", tenantId);
            throw new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED, SsoAuthService.SSO_ADMISSION_REJECTED_MSG, e);
        }
        auditRecorder.audit(provider, "BIND", SysAuditLog.RESULT_SUCCESS, null, target.userId(), externalDigest,
                "phone-admission auto-bind", tenantId);
        auditRecorder.audit(provider, "LOGIN_SUCCESS", SysAuditLog.RESULT_SUCCESS, null, target.userId(), externalDigest, null, tenantId);
        return new SsoAuthService.CallbackResult(provider, tenantId, externalId, externalDigest,
                target.userId(), stateRow.getRedirectPath(), true, configDigest);
    }

    /** 本地用户最小视图（准入链专用；phone 仅服务端内存使用，不进日志）。 */
    private record LocalUserView(Long userId, Long tenantId, Integer status, String phone) {
        boolean active() {
            return status == null || status == 0;
        }
    }

    private LocalUserView loadLocalUser(Long userId) {
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            SysUser user = sysUserService.getById(userId);
            if (user == null) {
                return null;
            }
            return new LocalUserView(user.getId(), user.getTenantId(), user.getStatus(), user.getPhone());
        }
    }

    /**
     * 在当前租户组织架构内按规范化手机号唯一匹配有效用户：租户内全量非空手机号
     * 规范化后比较（组织规模量级；不做任何"截后 N 位"近似）。零/多命中返回 null
     * 并由调用方经 count 区分审计原因。
     */
    private LocalUserView findUniqueTenantUserByPhone(Long tenantId, String normalizedPhone) {
        List<LocalUserView> hits = tenantUsersByPhone(tenantId, normalizedPhone);
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private long countTenantUsersByPhone(Long tenantId, String normalizedPhone) {
        return tenantUsersByPhone(tenantId, normalizedPhone).size();
    }

    private List<LocalUserView> tenantUsersByPhone(Long tenantId, String normalizedPhone) {
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            List<SysUser> users = sysUserService.list(
                    Wrappers.<SysUser>lambdaQuery()
                            .eq(SysUser::getTenantId, tenantId)
                            .isNotNull(SysUser::getPhone));
            return users.stream()
                    .map(u -> new LocalUserView(u.getId(), u.getTenantId(), u.getStatus(), u.getPhone()))
                    .filter(u -> u.active())
                    .filter(u -> normalizedPhone.equals(SsoPhoneNormalizer.normalize(u.phone())))
                    .toList();
        }
    }

    // ==================== 绑定 / 解绑 ====================

    /**
     * 绑定外部身份到本地账号（必须已认证；同租户内冲突/重复绑定显式拒绝）。
     */
    void bind(String provider, Long userId, String externalId) {
        SsoAuthService.requireProvider(provider);
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new SsoRejectionException(SystemErrorKeys.SESSION_REQUIRED, SsoAuthService.SESSION_REQUIRED_MSG);
        }
        Long tenantId = current.getTenantId();
        tenantValidityService.requireValid(tenantId);
        // 摘要即权威：明文 externalId 不落 SQL/日志（I5 复验 G7b）
        String externalDigest = SsoAuthService.sha256(externalId);
        SsoUserBinding existingExternal = bindingMapper.selectActiveByExternal(provider, tenantId, externalDigest);
        if (existingExternal != null) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFLICT_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), current.getUserId(), existingExternal.getUserId(),
                    externalDigest, "external id already bound");
            throw new SsoRejectionException(SystemErrorKeys.SSO_BINDING_CONFLICT,
                    "该第三方账号已绑定到其他账号，请先在该账号解绑，或改用其他第三方账号");
        }
        SsoUserBinding existingUser = bindingMapper.selectActiveByUser(provider, tenantId, userId);
        if (existingUser != null) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFLICT_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), current.getUserId(), userId,
                    externalDigest, "user already bound to another external id");
            throw new SsoRejectionException(SystemErrorKeys.SSO_BINDING_CONFLICT,
                    "当前账号已绑定其他第三方账号，请先解绑后再绑定新的");
        }
        // 跨租户稳定主体冲突（I5 复验 G6a/V87）：同一 (provider, 摘要) 全局仅允许一个租户绑定
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            Long crossTenant = bindingMapper.selectCount(
                    Wrappers.<SsoUserBinding>lambdaQuery()
                            .eq(SsoUserBinding::getProvider, provider)
                            .eq(SsoUserBinding::getExternalDigest, externalDigest)
                            .eq(SsoUserBinding::getBindStatus, SsoBindStatusEnum.ACTIVE.getCode())
                            .ne(SsoUserBinding::getTenantId, tenantId));
            if (crossTenant != null && crossTenant > 0) {
                auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFLICT_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), current.getUserId(), userId,
                        externalDigest, "external subject already bound in another tenant");
                throw new SsoRejectionException(SystemErrorKeys.SSO_BINDING_CONFLICT,
                        "该第三方账号当前无法绑定，请联系管理员处理");
            }
        }
        SsoUserBinding binding = new SsoUserBinding();
        binding.setProvider(provider);
        binding.setTenantId(tenantId);
        binding.setExternalId(externalDigest);
        binding.setExternalDigest(externalDigest);
        binding.setUserId(userId);
        binding.setBindStatus(SsoBindStatusEnum.ACTIVE.getCode());
        try {
            bindingMapper.insert(binding);
        } catch (DuplicateKeyException e) {
            // 并发绑定以数据库全局唯一键为最终仲裁；把失败侧收敛为可判定的业务拒绝，
            // 避免控制器把唯一键竞争暴露成 500，也不创建角色/会话副作用。
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFLICT_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), current.getUserId(), userId,
                    externalDigest, "binding unique constraint rejected");
            throw new SsoRejectionException(SystemErrorKeys.SSO_BINDING_CONFLICT,
                    "该第三方账号当前无法绑定，请联系管理员处理", e);
        }
        auditRecorder.audit(provider, "BIND", SysAuditLog.RESULT_SUCCESS, current.getUserId(), userId, externalDigest, null);
    }

    /**
     * 解绑：本地账号在当前租户内对指定 Provider 的有效绑定置 UNBOUND；
     * 并按方向 §3.2「第三方解绑触发与风险相称的会话撤销」撤销当前会话：
     * 清除登录缓存 + 按当前 access token 摘要写撤销标记（token 维度——同用户随后
     * 建立的新会话不受影响，旧 token 无法借新会话的 userId 缓存复活）+ 撤销全部
     * 既有 refresh token。
     */
    void unbind(String provider, Long userId, String currentToken) {
        SsoAuthService.requireProvider(provider);
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new SsoRejectionException(SystemErrorKeys.SESSION_REQUIRED, SsoAuthService.SESSION_REQUIRED_MSG);
        }
        tenantValidityService.requireValid(current.getTenantId());
        SsoUserBinding binding = bindingMapper.selectActiveByUser(provider, current.getTenantId(), userId);
        if (binding == null) {
            throw new SsoRejectionException(SystemErrorKeys.SSO_BINDING_NOT_FOUND, "当前账号没有该第三方账号的绑定");
        }
        binding.setBindStatus(SsoBindStatusEnum.UNBOUND.getCode());
        bindingMapper.updateById(binding);
        auditRecorder.audit(provider, "UNBIND", SysAuditLog.RESULT_SUCCESS, current.getUserId(), userId, binding.getExternalDigest(), null);
        // 会话撤销（I5 §3.2）：清缓存 + token 摘要撤销标记（过滤器装载前检查）+ 撤 refresh
        if (loginUserCacheService != null) {
            loginUserCacheService.evict(userId);
            if (currentToken != null && !currentToken.isBlank()) {
                loginUserCacheService.markTokenRevoked(currentToken);
            }
        }
        if (refreshTokenService != null) {
            refreshTokenService.revokeAllForUserPublic(userId);
        }
    }
}
