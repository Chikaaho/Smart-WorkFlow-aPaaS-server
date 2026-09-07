package com.sw.ck.system.security;

import java.time.Duration;
import java.time.Instant;

/**
 * 登录挑战权威状态存储（P45）。
 * <p>
 * 生产实现基于跨实例共享 Redis；挑战的一次性消费必须是原子裁决
 * （{@link #consume} 语义：仅当挑战仍存在时删除并返回 true），
 * 禁止以单实例本地内存作为权威状态。
 */
public interface LoginChallengeStore {

    /** 新建挑战记录；ttl 为记录保留期（须长于业务有效期），由实现写入权威存储 */
    void save(String captchaId, LoginChallengeRecord record, Duration ttl);

    /** 读取挑战记录；不存在（含已过期/已消费）返回 null */
    LoginChallengeRecord find(String captchaId);

    /**
     * 原子消费挑战：仅当挑战仍存在时删除并返回 true。
     * 并发提交下最多一个请求能拿到 true，其余全部失败且无副作用。
     */
    boolean consume(String captchaId);

    /** 直接删除挑战（如验证码失败次数超限后作废） */
    void delete(String captchaId);

    /**
     * 记录一次验证码内容失败；返回累计失败次数。
     * 失败计数与记录保留期同 TTL，超限后由调用方删除挑战。
     */
    long recordCaptchaFailure(String captchaId, Duration ttl);

    /** 挑战记录（存储只落验证码摘要，不落原文；createdAtEpochMs 为生成时间 Unix epoch 毫秒） */
    record LoginChallengeRecord(String captchaDigest, String keyVersion, long createdAtEpochMs) {
    }
}
