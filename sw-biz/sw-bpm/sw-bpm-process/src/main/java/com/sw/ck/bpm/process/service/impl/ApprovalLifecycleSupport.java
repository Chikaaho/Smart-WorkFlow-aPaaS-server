package com.sw.ck.bpm.process.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 生命周期动作公共支撑（自 ApprovalLifecycleServiceImpl 拆出，行为不变）。
 * <p>
 * 承载各动作执行协作类共享的动作资格校验（登录态 / 租户归属 / 目标人员有效性）、
 * 审计落库（任务级 recordLifecycle / 实例级 recordInstance）、统一通知触发
 * （失败不反转审批状态）与 JSON / 时间解析工具方法。事务边界仍保留在
 * {@link ApprovalLifecycleServiceImpl} 门面的公开方法上，本类方法均在既有
 * 事务内执行，不自带事务注解。
 * </p>
 */
@Service
@Log4j2
class ApprovalLifecycleSupport {

    /** 加签类型：追加加签 */
    static final String SIGN_TYPE_ADD = "ADD_SIGN";
    /** 加签类型：补签 */
    static final String SIGN_TYPE_SUPPLEMENT = "SUPPLEMENT_SIGN";
    /** 加签模式：串行 */
    static final String MODE_SERIAL = "SERIAL";
    /** 加签模式：并行 */
    static final String MODE_PARALLEL = "PARALLEL";

    @Autowired
    private ApprovalActionService approvalActionService;
    @Autowired
    private ObjectProvider<TaskActionService> taskActionService;
    @Autowired
    private ObjectProvider<UserQueryFacade> userQueryFacade;
    @Autowired
    private DomainEventPublisher domainEventPublisher;
    @Autowired
    private ObjectMapper objectMapper;

    LoginUser requireAuthenticatedActor() {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null || actor.getUserId() == null || actor.getTenantId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        return actor;
    }

    void requireSameTenant(Long objectTenantId, LoginUser actor, String message) {
        if (!Objects.equals(objectTenantId, actor.getTenantId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), message);
        }
    }

    void requireActiveUser(Long userId, Long tenantId) {
        UserQueryFacade facade = userQueryFacade.getIfAvailable();
        if (facade == null) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(), "用户权威不可用");
        }
        // empty = 查询对象/租户上下文缺失：无法确认有效性，按原有“无效目标”拒绝
        List<Long> active = facade.findActiveUserIds(List.of(userId), tenantId)
                .orElse(List.of());
        if (!active.contains(userId)) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(),
                    "目标人员无效或不属于当前租户: " + userId);
        }
    }

    void recordLifecycle(BpmTaskVO task, LoginUser actor, ApprovalAction action,
                         String settlement, ApprovalActionRequest request,
                         Map<String, Object> detail) {
        if (task == null) {
            return;
        }
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setProcessInstanceId(task.getProcessInstanceId());
        record.setNodeKey(task.getTaskDefinitionKey() == null
                ? task.getTaskId() : task.getTaskDefinitionKey());
        record.setTaskId(task.getTaskId());
        record.setActorId(actor.getUserId());
        record.setAction(action.name());
        if (request.getTargetUserId() != null) {
            record.setTargetUserId(request.getTargetUserId());
        }
        if (request.getOpinionFormId() != null) {
            record.setOpinionFormId(request.getOpinionFormId());
            record.setOpinionFormVersion(request.getOpinionFormVersion());
        }
        try {
            record.setOpinionData(objectMapper.writeValueAsString(
                    request.getOpinionData() == null ? Map.of() : request.getOpinionData()));
            record.setDetail(objectMapper.writeValueAsString(detail == null ? Map.of() : detail));
            // I3 意见表单不可变快照：生命周期动作（加签/补签表态等）执行意见表单时
            // 与主审批同口径落快照，保证五类轮次的历史回显不漂移（§4.10）。
            if (request.getOpinionFormId() != null) {
                Map<String, Object> opinionSnapshot = new LinkedHashMap<>();
                opinionSnapshot.put("opinionForm", taskActionService.getObject().resolveOpinionForm(task));
                opinionSnapshot.put("opinionFormId", request.getOpinionFormId());
                opinionSnapshot.put("opinionFormVersion", request.getOpinionFormVersion());
                record.setOpinionFormSnapshot(objectMapper.writeValueAsString(opinionSnapshot));
            }
        } catch (Exception e) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
        record.setSettlementStatus(settlement);
        record.setTenantId(actor.getTenantId());
        // V74 唯一键 (tenant, task, actor, action) 幂等写入：加签取消等复用同键动作行，
        // 以最新结算状态覆盖，不产生第二行也不触发唯一键冲突
        approvalActionService.upsertDuplicate(record);
    }

    void recordInstance(BpmInstance instance, LoginUser actor, ApprovalAction action,
                        String settlement, Map<String, Object> detail) {
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setProcessInstanceId(instance.getProcessInstanceId());
        record.setNodeKey("INSTANCE");
        record.setTaskId("ACTION#" + instance.getProcessInstanceId());
        record.setActorId(actor.getUserId());
        record.setAction(action.name());
        try {
            record.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            record.setDetail("{}");
        }
        record.setSettlementStatus(settlement);
        record.setTenantId(instance.getTenantId() == null
                ? actor.getTenantId() : instance.getTenantId());
        approvalActionService.save(record);
    }

    void publishNotice(LoginUser actor, String processInstanceId, Long recipient,
                       BpmNotifyTrigger trigger) {
        try {
            if (actor == null || actor.getTenantId() == null) {
                // 通知事件租户缺失属异常路径，fail closed 不落租户 0
                throw new IllegalArgumentException("通知事件缺少租户上下文: processInstanceId=" + processInstanceId);
            }
            domainEventPublisher.publish(new BpmNotifyEvent(
                    trigger, recipient,
                    actor.getTenantId(),
                    actor.getUserId(),
                    processInstanceId));
        } catch (Exception e) {
            log.warn("通知事件发布失败（审批状态不回滚）: trigger={}, error={}", trigger, e.getMessage());
        }
    }

    LocalDateTime parseTime(String raw, BpmErrorCode error) {
        LocalDateTime result = parseTimeSilently(raw);
        if (raw != null && !raw.isBlank() && result == null
                && !"null".equalsIgnoreCase(raw.trim())) {
            throw new BaseException(error.getCode(), "时间格式不合法: " + raw);
        }
        return result;
    }

    LocalDateTime parseTimeSilently(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    String nullSafe(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank()
                ? fallback : String.valueOf(value);
    }

    String nullSafe(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    Map<String, Object> mergeDetails(String raw, Map<String, Object> extra) {
        try {
            Map<String, Object> merged = raw == null || raw.isBlank()
                    ? new LinkedHashMap<>()
                    : objectMapper.readValue(raw, Map.class);
            merged.putAll(extra);
            return merged;
        } catch (Exception e) {
            return new LinkedHashMap<>(extra);
        }
    }

    String extractDetailField(String detail, String key) {
        try {
            if (detail == null || detail.isBlank()) {
                return null;
            }
            Object value = objectMapper.readValue(detail, Map.class).get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }
}
