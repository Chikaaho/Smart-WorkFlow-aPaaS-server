package com.sw.ck.openapi.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.openapi.api.exception.OpenApiErrorCode;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.entity.OpenApiNonce;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiNonceMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 开放接口入站鉴权（I4 §3.4）。
 * <p>
 * 签名口径：HMAC-SHA256(secret, appId + timestamp + nonce + sha256(body))，十六进制小写。
 * 防重放：时间戳窗口 ±300s + nonce 唯一约束（DB 权威）；scope 校验 + 租户边界；
 * 校验通过后还原应用绑定用户的受控代理上下文（与手动提交同一校验路径的运行前提）。
 * </p>
 */
@Service
public class OpenApiAuthService {

    static final long WINDOW_SECONDS = 300;

    private final OpenApiAppMapper appMapper;
    private final OpenApiNonceMapper nonceMapper;
    private final com.sw.ck.system.api.tenant.TenantValidityFacade tenantValidityFacade;

    public OpenApiAuthService(OpenApiAppMapper appMapper, OpenApiNonceMapper nonceMapper,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              com.sw.ck.system.api.tenant.TenantValidityFacade tenantValidityFacade) {
        this.appMapper = appMapper;
        this.nonceMapper = nonceMapper;
        this.tenantValidityFacade = tenantValidityFacade;
    }

    /** 校验签名/时间窗/防重放/scope，通过后建立代理上下文并返回应用。 */
    public OpenApiAuthContext authenticate(String appId, String timestamp, String nonce,
                                           String signature, String rawBody, String requiredScope) {
        // 认证前无登录态：app/nonce 读取与写入显式挂起租户过滤
        // （租户语义由 app 行 tenantId 与代理上下文权威承担）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return doAuthenticate(appId, timestamp, nonce, signature, rawBody, requiredScope);
        }
    }

    private OpenApiAuthContext doAuthenticate(String appId, String timestamp, String nonce,
                                              String signature, String rawBody, String requiredScope) {
        if (appId == null || timestamp == null || nonce == null || signature == null) {
            throw new BaseException(OpenApiErrorCode.SIGN_INVALID.getCode(),
                    "缺少鉴权头（X-App-Id / X-Timestamp / X-Nonce / X-Signature）");
        }
        OpenApiApp app = appMapper.selectOne(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getAppId, appId).eq(OpenApiApp::getDeleted, 0));
        if (app == null) {
            throw new BaseException(OpenApiErrorCode.APP_NOT_FOUND);
        }
        if (!"ENABLED".equals(app.getStatus())) {
            throw new BaseException(OpenApiErrorCode.APP_DISABLED);
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new BaseException(OpenApiErrorCode.TIMESTAMP_EXPIRED);
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - ts) > WINDOW_SECONDS) {
            throw new BaseException(OpenApiErrorCode.TIMESTAMP_EXPIRED);
        }
        String expected = sign(app.getSecretHash(), appId, timestamp, nonce, rawBody);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new BaseException(OpenApiErrorCode.SIGN_INVALID);
        }
        // 租户有效性必须早于 nonce 写入：停用/过期租户的签名请求不得留下任何
        // 认证副作用，也不得建立代理上下文。
        // 判定为 fail-closed：present-false 与 empty（无法判定）一律视为无效租户。
        if (tenantValidityFacade != null
                && tenantValidityFacade.isValid(app.getTenantId())
                        .filter(Boolean::booleanValue).isEmpty()) {
            throw new BaseException(OpenApiErrorCode.TENANT_INVALID);
        }
        try {
            OpenApiNonce row = new OpenApiNonce();
            row.setAppId(appId);
            row.setTenantId(app.getTenantId());
            row.setNonce(nonce);
            row.setExpireAt(LocalDateTime.now().plusSeconds(WINDOW_SECONDS * 2));
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            row.setDeleted(0);
            // 认证前无登录态：挂起租户过滤（租户归 app 行权威，显式携带）
            try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                         com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
                nonceMapper.insert(row); // 唯一键兜底并发重放
            }
        } catch (DuplicateKeyException e) {
            throw new BaseException(OpenApiErrorCode.NONCE_REUSED);
        }
        List<String> scopes = app.getScopes() == null ? List.of()
                : List.of(app.getScopes().split(","));
        if (requiredScope != null && !scopes.contains(requiredScope)) {
            throw new BaseException(OpenApiErrorCode.SCOPE_DENIED);
        }
        LoginUser user = new LoginUser();
        user.setUserId(app.getActAsUserId());
        user.setTenantId(app.getTenantId());
        LoginUserHolder.set(user);
        return new OpenApiAuthContext(appId, app.getTenantId(), app.getActAsUserId(), scopes);
    }

    /** secret 经 SHA-256 出库即摘要——签名直接使用摘要字节（存储零明文）。 */
    public static String sign(String secret, String appId, String timestamp,
                              String nonce, String body) {
        String bodyHash = sha256(body == null ? "" : body);
        String material = appId + timestamp + nonce + bodyHash;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    public record OpenApiAuthContext(String appId, Long tenantId, Long actAsUserId,
                                     List<String> scopes) {
    }
}
