package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.common.trace.EventRef;
import com.sw.ck.system.sso.SsoAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 租户级 SSO 配置管理控制器（sso-admin-config §二/§三）。
 * <p>
 * 查看/编辑/启停/凭据更新分别受独立服务端权限守卫；租户来自认证上下文，客户端
 * 不能覆盖。secret 只写：请求可携带新值，响应与页面永不回显原文/密文；留空保留
 * 旧值。配置检查只验证完整性与可解密性，不发起用户授权、不宣称真实登录通过。
 * 企业微信 Owner 延期：保留既有配置记录只读标注，不提供变更入口。
 * </p>
 */
@RestController
@RequestMapping("/system/sso/config")
public class SsoConfigController {

    private static final Logger log = LoggerFactory.getLogger(SsoConfigController.class);

    private final SsoAuthService ssoAuthService;

    public SsoConfigController(SsoAuthService ssoAuthService) {
        this.ssoAuthService = ssoAuthService;
    }

    /** 租户配置列表（含未登记平台占位；secret 只回是否已配置）。 */
    @PreAuthorize("@ss.hasPermi('system:sso:config:list')")
    @GetMapping
    public R<Map<String, Object>> list() {
        return R.ok(Map.of("configs", ssoAuthService.listConfigs()));
    }

    /** 更新基本信息（应用标识＋身份模式/允许企业标识；secret 不在本入口）。 */
    @PreAuthorize("@ss.hasPermi('system:sso:config:edit')")
    @PutMapping("/{provider}/basic")
    public R<Void> updateBasic(@PathVariable("provider") String provider,
                               @RequestBody BasicRequest request) {
        try {
            ssoAuthService.updateConfigBasic(provider, request.appId(), request.extraConfig());
            return R.ok();
        } catch (RuntimeException e) {
            log.warn("SSO 配置基本信息更新被拒: eventRef={} provider={} detail={}",
                    EventRef.current(), provider, e.getMessage());
            return R.failResolved(400, "system.sso_config_invalid",
                    e.getMessage() == null ? "配置未能保存，请核对输入" : e.getMessage(), EventRef.current());
        }
    }

    /** 启停（保存后对后续授权生效；不冒称已登出既有会话）。 */
    @PreAuthorize("@ss.hasPermi('system:sso:config:enable')")
    @PutMapping("/{provider}/enabled")
    public R<Void> updateEnabled(@PathVariable("provider") String provider,
                                 @RequestBody EnabledRequest request) {
        try {
            ssoAuthService.updateEnabled(provider, request.enabled() != null && request.enabled());
            return R.ok();
        } catch (RuntimeException e) {
            log.warn("SSO 配置启停被拒: eventRef={} provider={} enabled={} detail={}",
                    EventRef.current(), provider, request.enabled(), e.getMessage());
            return R.failResolved(400, "system.sso_config_invalid",
                    e.getMessage() == null ? "操作未能完成，请稍后重试" : e.getMessage(), EventRef.current());
        }
    }

    /** 应用凭据更新（secret 只写；掩码/占位/空值拒绝；与主密钥轮换无关）。 */
    @PreAuthorize("@ss.hasPermi('system:sso:config:secret')")
    @PutMapping("/{provider}/secret")
    public R<Void> updateSecret(@PathVariable("provider") String provider,
                                @RequestBody SecretRequest request) {
        try {
            ssoAuthService.updateSecret(provider, request.appSecret());
            return R.ok();
        } catch (RuntimeException e) {
            log.warn("SSO 应用凭据更新被拒: eventRef={} provider={} detail={}",
                    EventRef.current(), provider, e.getMessage());
            return R.failResolved(400, "system.sso_config_invalid",
                    e.getMessage() == null ? "凭据未能更新，请核对输入" : e.getMessage(), EventRef.current());
        }
    }

    /** 配置检查（只读；不修改配置/绑定，不宣称真实登录通过）。 */
    @PreAuthorize("@ss.hasPermi('system:sso:config:list')")
    @GetMapping("/{provider}/check")
    public R<Map<String, Object>> check(@PathVariable("provider") String provider) {
        try {
            return R.ok(ssoAuthService.checkConfig(provider));
        } catch (RuntimeException e) {
            log.warn("SSO 配置检查失败: eventRef={} provider={} detail={}",
                    EventRef.current(), provider, e.getMessage());
            return R.failResolved(400, "system.sso_config_invalid",
                    e.getMessage() == null ? "配置检查未能完成" : e.getMessage(), EventRef.current());
        }
    }

    public record BasicRequest(String appId, String extraConfig) {
    }

    public record EnabledRequest(Boolean enabled) {
    }

    public record SecretRequest(String appSecret) {
    }
}
