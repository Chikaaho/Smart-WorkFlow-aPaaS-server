package com.sw.ck.security.cache;

import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.jwt.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * LoginUser 的 Redis 缓存读写，key 以 userId 维度组织。
 * <p>
 * TTL 与 JWT 过期时间保持一致：token 仍在有效期内时，缓存也应同时失效，强制下一次请求
 * 重新走 {@link com.sw.ck.security.spi.UserDetailsProvider} 回查，避免长期持有陈旧权限。
 * {@link #evict} 额外用于主动踢人下线/权限变更立即生效的场景。
 */
@RequiredArgsConstructor
public class LoginUserCacheService {

    private static final String KEY_PREFIX = "sw:security:login-user:";
    private static final String TOKEN_REVOKED_PREFIX = "sw:security:token-revoked:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtProperties jwtProperties;

    public void cache(LoginUser loginUser) {
        long ttlSeconds = jwtProperties.getAccessExpireSeconds() > 0
                ? jwtProperties.getAccessExpireSeconds()
                : jwtProperties.getExpireSeconds();
        redisTemplate.opsForValue().set(buildKey(loginUser.getUserId()), loginUser,
                ttlSeconds, TimeUnit.SECONDS);
    }

    public LoginUser get(Long userId) {
        return (LoginUser) redisTemplate.opsForValue().get(buildKey(userId));
    }

    public void evict(Long userId) {
        redisTemplate.delete(buildKey(userId));
    }

    /**
     * 会话撤销标记（I5 §3.2 第三方解绑）：按 access token 摘要（SHA-256）记录，
     * TTL 与 access 过期一致——覆盖被撤销 token 的剩余寿命；窗口过后旧 token 必然
     * 已自然过期。token 维度保证同用户随后建立的新会话（新 token）不受影响，
     * 旧 token 也无法借新会话的 userId 缓存复活。
     */
    public void markTokenRevoked(String rawToken) {
        long ttlSeconds = jwtProperties.getAccessExpireSeconds() > 0
                ? jwtProperties.getAccessExpireSeconds()
                : jwtProperties.getExpireSeconds();
        redisTemplate.opsForValue().set(buildTokenRevokedKey(digest(rawToken)), "1",
                ttlSeconds, TimeUnit.SECONDS);
    }

    public boolean isTokenRevoked(String rawToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildTokenRevokedKey(digest(rawToken))));
    }

    private String buildKey(Long userId) {
        return KEY_PREFIX + userId;
    }

    private String buildTokenRevokedKey(String tokenDigest) {
        return TOKEN_REVOKED_PREFIX + tokenDigest;
    }

    private static String digest(String rawToken) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
