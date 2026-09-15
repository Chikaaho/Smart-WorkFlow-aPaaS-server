package com.sw.ck.system.controller;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSO 一次性票据存储（I5）。
 * <p>
 * 会话票据（bound ticket → userId/tenantId）与绑定候选票据（candidate ticket →
 * externalId）均为不可预测、限时（60s）、一次性消费；消费后立即删除。
 * 进程内实现与当前单实例部署一致；多实例演进时按 LoginUserCacheService 的
 * Redis 模式替换，接口语义不变。
 * </p>
 */
@Component
public class SsoTicketStore {

    private static final long TTL_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();

    /** 签发会话票据（已绑定登录）。 */
    public String issue(Long userId, Long tenantId) {
        return put(new Entry(EntryType.SESSION, null, null, userId, tenantId));
    }

    /** 签发绑定候选票据（未绑定回跳）。 */
    public String issueCandidate(String externalId, Long tenantId, String provider) {
        return put(new Entry(EntryType.CANDIDATE, externalId, provider, null, tenantId));
    }

    /** 原子消费会话票据：有效返回载荷并删除，否则返回 null。 */
    public ConsumedSession consumeSession(String ticket) {
        Entry entry = consume(ticket, EntryType.SESSION);
        return entry == null ? null : new ConsumedSession(entry.userId, entry.tenantId);
    }

    /** 原子消费绑定候选票据。 */
    public ConsumedCandidate consumeCandidate(String ticket) {
        Entry entry = consume(ticket, EntryType.CANDIDATE);
        return entry == null ? null : new ConsumedCandidate(entry.externalId, entry.tenantId, entry.provider);
    }

    /** 非消费式读取绑定候选票据（展示摘要用；消费仍走 consumeCandidate）。 */
    public ConsumedCandidate peekCandidate(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Entry entry = tickets.get(ticket);
        if (entry == null || entry.type != EntryType.CANDIDATE
                || entry.expireAt.isBefore(LocalDateTime.now())) {
            return null;
        }
        return new ConsumedCandidate(entry.externalId, entry.tenantId, entry.provider);
    }

    private synchronized Entry consume(String ticket, EntryType type) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        Entry entry = tickets.get(ticket);
        if (entry == null || entry.type != type || entry.expireAt.isBefore(LocalDateTime.now())) {
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

    public record ConsumedSession(Long userId, Long tenantId) {
    }

    public record ConsumedCandidate(String externalId, Long tenantId, String provider) {
    }

    private enum EntryType {SESSION, CANDIDATE}

    private static final class Entry {
        final EntryType type;
        final String externalId;
        final String provider;
        final Long userId;
        final Long tenantId;
        LocalDateTime expireAt;

        Entry(EntryType type, String externalId, String provider, Long userId, Long tenantId) {
            this.type = type;
            this.externalId = externalId;
            this.provider = provider;
            this.userId = userId;
            this.tenantId = tenantId;
        }
    }
}
