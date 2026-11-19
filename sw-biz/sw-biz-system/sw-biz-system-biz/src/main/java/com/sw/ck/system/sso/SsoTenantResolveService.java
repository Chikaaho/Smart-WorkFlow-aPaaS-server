package com.sw.ck.system.sso;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.system.entity.SsoProviderConfig;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.mapper.SsoProviderConfigMapper;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.service.TenantValidityService;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 登录前发起的租户解析：按名称精确解析（V012-BUG-019）与按 Provider 反查唯一
 * 可用企业（B021-016 免填企业）。自 SsoAuthService 拆出（行为不变）。
 */
@Service
@Log4j2
class SsoTenantResolveService {

    @Autowired
    private SysTenantMapper tenantMapper;
    @Autowired
    private SsoProviderConfigMapper configMapper;
    @Autowired
    private TenantValidityService tenantValidityService;

    /**
     * 登录前发起按租户名称精确解析（V012-BUG-019）：名称完全一致且唯一才返回租户 ID。
     * <p>
     * 零命中 → {@code sso_tenant_not_found}；多行命中 → {@code sso_tenant_ambiguous}
     * （名称歧义不任意选中）；空白 → {@code sso_tenant_required}。停用/过期等
     * 有效性仍由 {@code startAuthorizeLogin(String, Long, String)} 的既有校验承担。
     * 查询发生在免认证路径，挂起租户拦截器（sys_tenant 为全局表）。
     * </p>
     */
    Long resolveTenantIdByNameExact(String tenantName) {
        if (tenantName == null || tenantName.isBlank()) {
            throw new SsoRejectionException(SystemErrorKeys.SSO_TENANT_REQUIRED, SsoAuthService.SSO_TENANT_REQUIRED_MSG);
        }
        String name = tenantName.trim();
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            List<SysTenant> hits = tenantMapper.selectList(
                    Wrappers.<SysTenant>lambdaQuery()
                            .eq(SysTenant::getName, name));
            if (hits.isEmpty()) {
                throw new SsoRejectionException(SystemErrorKeys.SSO_TENANT_NOT_FOUND, SsoAuthService.SSO_TENANT_NOT_FOUND_MSG);
            }
            if (hits.size() > 1) {
                log.warn("SSO 租户名称解析命中多行: count={}", hits.size());
                throw new SsoRejectionException(SystemErrorKeys.SSO_TENANT_AMBIGUOUS, SsoAuthService.SSO_TENANT_AMBIGUOUS_MSG);
            }
            return hits.get(0).getId();
        }
    }

    /**
     * 登录页免填企业（B021-016）：按 Provider 反查启用配置——恰好一个可用企业
     * 启用该登录方式即视为目标；零命中/多行命中均 fail closed（多企业歧义后续
     * 由扫码选择所属企业承接，不在此处任意选中）。停用/过期企业不参与自动识别。
     * 查询发生在免认证路径，挂起租户拦截器（配置表按 tenant_id 显式过滤语义）。
     */
    Long resolveDefaultTenantIdForProvider(String provider) {
        SsoAuthService.requireProvider(provider);
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            List<SsoProviderConfig> hits = configMapper.selectList(
                    Wrappers.<SsoProviderConfig>lambdaQuery()
                            .eq(SsoProviderConfig::getProvider, provider)
                            .eq(SsoProviderConfig::getEnabled, 1));
            List<SsoProviderConfig> usable = new ArrayList<>();
            for (SsoProviderConfig hit : hits) {
                if (tenantValidityService.isValid(hit.getTenantId())) {
                    usable.add(hit);
                }
            }
            if (usable.isEmpty()) {
                throw new SsoRejectionException(SystemErrorKeys.SSO_TENANT_NOT_FOUND,
                        SsoAuthService.SSO_TENANT_PROVIDER_NONE_MSG);
            }
            if (usable.size() > 1) {
                log.warn("SSO 登录前发起按 Provider 反查命中多行: provider={} count={}",
                        provider, usable.size());
                throw new SsoRejectionException(SystemErrorKeys.SSO_TENANT_AMBIGUOUS,
                        SsoAuthService.SSO_TENANT_PROVIDER_AMBIGUOUS_MSG);
            }
            return usable.get(0).getTenantId();
        }
    }
}
