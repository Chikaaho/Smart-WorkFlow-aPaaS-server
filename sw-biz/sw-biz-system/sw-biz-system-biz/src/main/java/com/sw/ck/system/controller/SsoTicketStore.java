package com.sw.ck.system.controller;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSO 一次性票据存储（I5；sso-admin-config 收敛）。
 * <p>
 * 仅承载会话票据（bound ticket → userId/tenantId）：不可预测、限时（60s）、
 * 一次性消费；消费后立即删除。绑定候选票据已随 B 端手机号准入收敛移除——
 * 未绑定回跳不再进入手动候选绑定页，准入在回调链内完成或统一拒绝。
 * 进程内实现与当前单实例部署一致；多实例演进时按 LoginUserCacheService 的
 * Redis 模式替换，接口语义不变。
 * </p>
 */
@Component
public class SsoTicketStore {

    private static final long TTL_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();

    /** 签发会话票据（已绑定/准入绑定登录；携带 Provider 与签发时刻配置指纹——A4 在途票据语义：兑换时校验启用与指纹一致性，不串用新旧配置）。 */
    public String issue(Long userId, Long tenantId, String provider, String configDigest) {
        return put(new Entry(userId, tenantId, provider, configDigest));
    }

    /** 原子消费会话票据：有效返回载荷并删除，否则返回 null。 */
    public ConsumedSession consumeSession(String ticket) {
        Entry entry = consume(ticket);
        return entry == null ? null : new ConsumedSession(entry.userId, entry.tenantId, entry.provider, entry.configDigest);
    }

    private synchronized Entry consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Entry entry = tickets.get(ticket);
        if (entry == null || entry.expireAt.isBefore(LocalDateTime.now())) {
            tickets.remove(ticket);
            return null;
        }
        tickets.remove(ticket);
        return entry;
    }

    private String put(Entry entry) {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String ticket = HexFormat.of().formatHex(buf);
        entry.expireAt = LocalDateTime.now().plusSeconds(TTL_SECONDS);
        tickets.put(ticket, entry);
        return ticket;
    }

    public record ConsumedSession(Long userId, Long tenantId, String provider, String configDigest) {
    }

    private static final class Entry {
        final Long userId;
        final Long tenantId;
        final String provider;
        final String configDigest;
        LocalDateTime expireAt;

        Entry(Long userId, Long tenantId, String provider, String configDigest) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.provider = provider;
            this.configDigest = configDigest;
        }
    }
}
