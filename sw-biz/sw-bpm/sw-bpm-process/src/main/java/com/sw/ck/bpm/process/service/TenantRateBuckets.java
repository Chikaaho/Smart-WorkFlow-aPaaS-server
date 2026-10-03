package com.sw.ck.bpm.process.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 每租户工作单位速率桶（令牌桶；进程内实现）。
 * <p>
 * 合同：每租户速率上限 N 单位/s、突发额 B 单位（允许既有最大 500 项批次在额度
 * 充足时整笔准入）。单应用进程边界（方向固定画像）下进程内原子桶即构成准入事实；
 * 重启后桶重置为满额（突发预算重新可用）——速率桶只做准入整形，不承载占用会计，
 * 占用可从持久事实恢复。多进程部署不属本阶段画像，届时需外置共享桶。
 * </p>
 */
@Component
public class TenantRateBuckets {

    /** 懒建立、按租户隔离；桶内操作串行化（单租户吞吐 50/s，锁开销可忽略）。 */
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试消费指定单位数。
     *
     * @return true=消费成功；false=超出当前可用速率额度
     */
    public boolean tryConsume(Long tenantId, int ratePerSecond, int burstCapacity, int units) {
        if (units <= 0) {
            return true;
        }
        Bucket bucket = buckets.computeIfAbsent(tenantId, key -> new Bucket(burstCapacity));
        return bucket.tryConsume(ratePerSecond, burstCapacity, units);
    }

    /** 运维画像回读用：当前可用令牌数。 */
    public long availableTokens(Long tenantId, int burstCapacity) {
        Bucket bucket = buckets.get(tenantId);
        return bucket == null ? burstCapacity : bucket.available(burstCapacity);
    }

    /** 清空全部桶（测试隔离用）。 */
    public void reset() {
        buckets.clear();
    }

    private static final class Bucket {

        private long tokens;
        private long lastRefillNanos;

        Bucket(int burstCapacity) {
            this.tokens = burstCapacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(int ratePerSecond, int burstCapacity, int units) {
            refill(ratePerSecond, burstCapacity);
            if (tokens < units) {
                return false;
            }
            tokens -= units;
            return true;
        }

        synchronized long available(int burstCapacity) {
            refill(0, burstCapacity);
            return tokens;
        }

        private void refill(int ratePerSecond, int burstCapacity) {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (ratePerSecond > 0 && elapsed > 0) {
                tokens = Math.min(burstCapacity, tokens + elapsed * ratePerSecond / 1_000_000_000L);
            }
            lastRefillNanos = now;
        }
    }
}
