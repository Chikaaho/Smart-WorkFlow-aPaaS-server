package com.sw.ck.system.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisLoginChallengeStore} 行为测试：挑战落 Redis 的 key 前缀、TTL 与原子消费语义
 * （consume 直接委托 Redis DEL，Redis 单 key DEL 天然原子，并发提交最多一次成功）。
 */
@DisplayName("Redis 登录挑战存储")
class RedisLoginChallengeStoreTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
    private final RedisLoginChallengeStore store =
            new RedisLoginChallengeStore(redisTemplate, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("save：以 sw:auth:challenge: 前缀 + TTL 写入 JSON 记录（仅摘要，无验证码原文）")
    void save_shouldWriteJsonWithTtl() {
        store.save("uuid-1", RedisLoginChallengeStore.record("digest", "v1", 123L), Duration.ofSeconds(300));

        verify(valueOperations).set(contains(RedisLoginChallengeStore.CHALLENGE_KEY_PREFIX), anyString(),
                eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("find：命中返回记录，未命中返回 null")
    void find_shouldReturnRecordOrNull() {
        when(valueOperations.get(RedisLoginChallengeStore.CHALLENGE_KEY_PREFIX + "uuid-1"))
                .thenReturn("{\"captchaDigest\":\"d\",\"keyVersion\":\"v1\",\"createdAtEpochMs\":1}");
        when(valueOperations.get(RedisLoginChallengeStore.CHALLENGE_KEY_PREFIX + "uuid-2")).thenReturn(null);

        LoginChallengeStore.LoginChallengeRecord hit = store.find("uuid-1");
        assertThat(hit).isNotNull();
        assertThat(hit.captchaDigest()).isEqualTo("d");
        assertThat(store.find("uuid-2")).isNull();
    }

    @Test
    @DisplayName("consume：委托 Redis DEL；删除成功=true，键不存在=false（原子一次性消费）")
    void consume_shouldDelegateToDelete() {
        when(redisTemplate.delete(RedisLoginChallengeStore.CHALLENGE_KEY_PREFIX + "uuid-1")).thenReturn(true);
        when(redisTemplate.delete(RedisLoginChallengeStore.CHALLENGE_KEY_PREFIX + "uuid-2")).thenReturn(false);

        assertThat(store.consume("uuid-1")).isTrue();
        assertThat(store.consume("uuid-2")).isFalse();
    }

    @Test
    @DisplayName("recordCaptchaFailure：INCR 原子累加，首次写入设置 TTL")
    void recordCaptchaFailure_shouldIncrWithTtl() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        long count = store.recordCaptchaFailure("uuid-1", Duration.ofSeconds(300));

        assertThat(count).isEqualTo(1L);
        verify(redisTemplate).expire(contains(RedisLoginChallengeStore.FAIL_KEY_PREFIX), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("recordCaptchaFailure：非首次不重复设置 TTL")
    void recordCaptchaFailure_subsequent_shouldNotResetTtl() {
        when(valueOperations.increment(anyString())).thenReturn(3L);

        store.recordCaptchaFailure("uuid-1", Duration.ofSeconds(300));

        verify(redisTemplate, never()).expire(anyString(), eq(Duration.ofSeconds(300)));
    }
}
