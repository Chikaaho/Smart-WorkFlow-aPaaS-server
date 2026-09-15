package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.entity.SysRefreshToken;
import com.sw.ck.system.mapper.SysRefreshTokenMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh Token 服务 — 生成/校验/轮换/撤销。
 * <p>
 * refresh token = 32 字节安全随机数 → 十六进制编码（64 字符）
 * → SHA-256 哈希 → 存 sys_refresh_token.token_hash。
 * 原文经 httpOnly cookie 下发，服务端只存 hash。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;       // 256 bit 随机数
    private static final String TOKEN_HASH_ALGO = "SHA-256";

    private final SysRefreshTokenMapper sysRefreshTokenMapper;
    private final PlatformTransactionManager transactionManager;
    private final SecureRandom secureRandom = new SecureRandom();

    // ========== 公开 API ==========

    /**
     * 为用户创建新的 refresh token（登录时调用）。
     *
     * @param userId              用户 ID
     * @param tenantId            租户 ID
     * @param refreshExpireSeconds 过期秒数
     * @return 原始 refresh token 字符串（传给 CookieUtils.setRefreshCookie）
     */
    @Transactional(rollbackFor = Exception.class)
    public String createRefreshToken(Long userId, Long tenantId, long refreshExpireSeconds) {
        // 1. 生成随机 token
        String rawToken = generateRawToken();
        // 2. SHA-256 哈希
        String tokenHash = sha256(rawToken);
        // 3. 写入 DB
        SysRefreshToken entity = new SysRefreshToken();
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setTokenHash(tokenHash);
        entity.setExpiresAt(LocalDateTime.now().plusSeconds(refreshExpireSeconds));
        entity.setRevoked(0);
        sysRefreshTokenMapper.insert(entity);
        // 4. 返回原文给调用方（用于设置 cookie）
        log.debug("Created refresh token for userId={}, id={}", userId, entity.getId());
        return rawToken;
    }

    /**
     * 校验 refresh token 并执行轮换（/auth/refresh 调用）。
     * <p>
     * 轮换策略：验证通过后立即撤销旧 token → 签发新 token。
     * 若检测到重放（传入已撤销 token），撤销该用户全部 refresh token。
     *
     * @param rawToken            cookie 中的原始 refresh token
     * @param refreshExpireSeconds 新 token 过期秒数
     * @return RefreshTokenRotation（userId + tenantId + 新的原始 token）
     * @throws BaseException(UNAUTHORIZED) token 无效/过期/已撤销
     */
    @Transactional(rollbackFor = Exception.class)
    public RefreshTokenRotation rotateRefreshToken(String rawToken, long refreshExpireSeconds) {
        String tokenHash = sha256(rawToken);
        // refresh 请求无 access 会话，租户上下文缺失属正常路径；行级租户语义由行内
        // tenant_id 承载，查询按 token_hash 全局唯一索引定位（I5 fail-closed 兼容）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return rotateRefreshTokenSuspended(tokenHash, refreshExpireSeconds);
        }
    }

    private RefreshTokenRotation rotateRefreshTokenSuspended(String tokenHash, long refreshExpireSeconds) {
        // 1. 查找 token
        SysRefreshToken existing = sysRefreshTokenMapper.selectOne(
                new LambdaQueryWrapper<SysRefreshToken>()
                        .eq(SysRefreshToken::getTokenHash, tokenHash)
        );
        // 2. 不存在 → 无效
        if (existing == null) {
            log.warn("Refresh token not found in DB");
            throw new BaseException(401, "refresh token 无效");
        }
        // 3. 已撤销 → 重放攻击，撤销该用户全部 refresh token
        if (existing.getRevoked() != null && existing.getRevoked() == 1) {
            log.error("REPLAY DETECTED: revoked refresh token reused, userId={}, tokenId={}",
                    existing.getUserId(), existing.getId());
            // 在独立事务中执行撤销，确保在 BaseException 抛出前已 COMMIT，不被回滚
            new TransactionTemplate(transactionManager) {{
                setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            }}.executeWithoutResult(status -> revokeAllForUser(existing.getUserId()));
            throw new BaseException(401, "refresh token 已被使用过，全部会话已失效，请重新登录");
        }
        // 4. 已过期 → 拒绝
        if (existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("Refresh token expired for userId={}, tokenId={}", existing.getUserId(), existing.getId());
            // 在独立事务中执行撤销，确保在 BaseException 抛出前已 COMMIT，不被回滚
            new TransactionTemplate(transactionManager) {{
                setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            }}.executeWithoutResult(status -> revokeTokenById(existing.getId()));
            throw new BaseException(401, "refresh token 已过期，请重新登录");
        }
        // 5. 撤销旧 token
        revokeTokenById(existing.getId());
        // 6. 签发新 token（轮换）
        Long userId = existing.getUserId();
        Long tenantId = existing.getTenantId();
        String newToken = createRefreshToken(userId, tenantId, refreshExpireSeconds);
        log.debug("Refresh token rotated for userId={}, oldId={}", userId, existing.getId());
        return new RefreshTokenRotation(userId, tenantId, newToken);
    }

    /**
     * 撤销指定的 refresh token（/auth/logout 调用）。
     * 如果 cookie 中无 token 或 token 无效，静默成功（幂等）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) {
            return; // 幂等：无 token 即已登出
        }
        // 登出可能发生在 access 过期后（无租户上下文）；按 token_hash 全局定位
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            String tokenHash = sha256(rawToken);
            SysRefreshToken existing = sysRefreshTokenMapper.selectOne(
                    new LambdaQueryWrapper<SysRefreshToken>()
                            .eq(SysRefreshToken::getTokenHash, tokenHash)
            );
            if (existing == null) {
                return; // 幂等：token 不存在即已登出
            }
            revokeTokenById(existing.getId());
            log.debug("Refresh token revoked for userId={}, tokenId={}", existing.getUserId(), existing.getId());
        }
    }

    /**
     * 查询 refresh token 关联的用户 ID（仅用于日志/审计，不做鉴权）。
     */
    public Long findUserIdByToken(String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) return null;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            String tokenHash = sha256(rawToken);
            SysRefreshToken existing = sysRefreshTokenMapper.selectOne(
                    new LambdaQueryWrapper<SysRefreshToken>()
                            .eq(SysRefreshToken::getTokenHash, tokenHash)
            );
            return existing != null ? existing.getUserId() : null;
        }
    }

    // ========== 内部方法 ==========

    /** 生成 32 字节安全随机数 → 64 字符十六进制字符串 */
    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** SHA-256 哈希 → 64 字符十六进制字符串 */
    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(TOKEN_HASH_ALGO);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** 撤销单个 token（按 id 更新 revoked=1） */
    private void revokeTokenById(Long id) {
        sysRefreshTokenMapper.update(null,
                new LambdaUpdateWrapper<SysRefreshToken>()
                        .eq(SysRefreshToken::getId, id)
                        .set(SysRefreshToken::getRevoked, 1));
    }

    /** 重放检测：撤销用户全部 refresh token */
    private void revokeAllForUser(Long userId) {
        sysRefreshTokenMapper.update(null,
                new LambdaUpdateWrapper<SysRefreshToken>()
                        .eq(SysRefreshToken::getUserId, userId)
                        .eq(SysRefreshToken::getRevoked, 0)
                        .set(SysRefreshToken::getRevoked, 1));
    }

    /**
     * 公开撤销入口（I5 §3.2 第三方解绑会话撤销）：撤销该用户全部有效 refresh token。
     */
    public void revokeAllForUserPublic(Long userId) {
        revokeAllForUser(userId);
    }

    // ========== 内部 DTO ==========

    /**
     * refresh token 轮换结果。
     */
    public record RefreshTokenRotation(Long userId, Long tenantId, String newRawToken) {
    }
}
