package com.sw.ck.iot.service;

import com.sw.ck.iot.entity.IotAuditRecord;
import com.sw.ck.iot.mapper.IotAuditRecordMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * IoT 行为审计写入服务。
 * <p>
 * 人工请求记录当前用户；后台事件记录显式系统身份。两者都保留 actor_id，
 * correlation_id 始终由调用方或本服务生成，避免审计行出现不可关联的空值。
 * </p>
 */
@Service
public class IotAuditService {

    private final IotAuditRecordMapper mapper;

    public IotAuditService(IotAuditRecordMapper mapper) {
        this.mapper = mapper;
    }

    public IotAuditRecord recordAction(Long tenantId,
                                       Long actorId,
                                       String systemIdentity,
                                       String action,
                                       String objectType,
                                       String objectId,
                                       String result,
                                       String correlationId,
                                       String detail) {
        LoginUser current = LoginUserHolder.get();
        Long resolvedTenantId = tenantId != null ? tenantId
                : current == null ? 0L : current.getTenantId();
        Long resolvedActorId = actorId != null ? actorId
                : current == null || current.getUserId() == null ? 0L : current.getUserId();
        String resolvedIdentity = systemIdentity == null || systemIdentity.isBlank()
                ? current == null || current.getUsername() == null
                ? "system:iot" : "user:" + current.getUsername()
                : systemIdentity;
        String resolvedCorrelation = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString() : correlationId;

        IotAuditRecord audit = new IotAuditRecord();
        audit.setTenantId(resolvedTenantId == null ? 0L : resolvedTenantId);
        audit.setAction(require(action, "action"));
        audit.setObjectType(require(objectType, "objectType"));
        audit.setObjectId(require(objectId, "objectId"));
        audit.setResult(require(result, "result"));
        audit.setActorId(resolvedActorId);
        audit.setSystemIdentity(resolvedIdentity);
        audit.setActionTime(LocalDateTime.now());
        audit.setCorrelationId(resolvedCorrelation);
        audit.setDetail(sanitizeDetail(detail));
        mapper.insert(audit);
        return audit;
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("审计字段不能为空: " + field);
        }
        return value;
    }

    private String sanitizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String sanitized = detail.replaceAll("(?i)(password|secret|token|credential)\\s*[=:]\\s*[^,; ]+",
                "$1=[MASKED]");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000);
    }
}
