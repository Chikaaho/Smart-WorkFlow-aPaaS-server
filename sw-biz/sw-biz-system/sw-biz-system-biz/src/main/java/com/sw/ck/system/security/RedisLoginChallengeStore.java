package com.sw.ck.system.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 基于跨实例共享 Redis 的登录挑战权威状态存储（P45）。
 * <p>
 * - 挑战记录以 JSON 字符串落 Redis（key: {@value #CHALLENGE_KEY_PREFIX}{uuid}，TTL 同挑战有效期）；
 * - 一次性消费直接依赖 Redis 单 key {@code DEL} 的原子性：并发提交下只有删除成功的那一个请求
 *   进入密码认证；
 * - 验证码失败计数使用独立 key（{@value #FAIL_KEY_PREFIX}{uuid}，INCR 原子累加，TTL 同挑战）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLoginChallengeStore implements LoginChallengeStore {

    static final String CHALLENGE_KEY_PREFIX = "sw:auth:challenge:";
    static final String FAIL_KEY_PREFIX = "sw:auth:challenge:fail:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String captchaId, LoginChallengeRecord record, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(CHALLENGE_KEY_PREFIX + captchaId,
                    objectMapper.writeValueAsString(record), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("登录挑战写入 Redis 失败", e);
        }
    }

    @Override
    public LoginChallengeRecord find(String captchaId) {
        Object raw = redisTemplate.opsForValue().get(CHALLENGE_KEY_PREFIX + captchaId);
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw.toString(), LoginChallengeRecord.class);
        } catch (Exception e) {
            // 记录损坏按不存在处理并清理，避免挑战长期滞留
            log.warn("登录挑战记录解析失败，已作废: {}", e.getMessage());
            redisTemplate.delete(CHALLENGE_KEY_PREFIX + captchaId);
            return null;
        }
    }

    @Override
    public boolean consume(String captchaId) {
        Boolean deleted = redisTemplate.delete(CHALLENGE_KEY_PREFIX + captchaId);
        return Boolean.TRUE.equals(deleted);
    }

    @Override
    public void delete(String captchaId) {
        redisTemplate.delete(CHALLENGE_KEY_PREFIX + captchaId);
    }

    @Override
    public long recordCaptchaFailure(String captchaId, Duration ttl) {
        String key = FAIL_KEY_PREFIX + captchaId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return count == null ? 0L : count;
    }

    /** 直接构造记录的辅助（createdAtEpochMs 为生成时间 Unix epoch 毫秒） */
    public static LoginChallengeRecord record(String captchaDigest, String keyVersion, long createdAtEpochMs) {
        return new LoginChallengeRecord(captchaDigest, keyVersion, createdAtEpochMs);
    }
}
