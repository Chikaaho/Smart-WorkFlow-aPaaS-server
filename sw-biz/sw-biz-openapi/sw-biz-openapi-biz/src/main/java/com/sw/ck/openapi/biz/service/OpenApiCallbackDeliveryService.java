package com.sw.ck.openapi.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.openapi.api.exception.OpenApiErrorCode;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.entity.OpenApiCallbackLog;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiCallbackLogMapper;
import cn.hutool.http.HttpRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 出站回调投递（I4 §3.4）：签名、重试、去重、失败可查、恢复重发。
 * <p>
 * 投递逻辑唯一实现在本服务；{@code OpenApiCallbackListener} 的事件转发与本服务的
 * 恢复重发（resend）共用同一签名/重试/去重路径，不保留第二套投递语义。
 * 去重权威是 (app, event, bizRef) 的 SUCCESS 记录：已成功投递的事件不再产生新回调，
 * 因此重发对已成功事件零新增；最终失败的事件在接收端恢复后可安全重发一次。
 * </p>
 */
@Service
public class OpenApiCallbackDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCallbackDeliveryService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 3;

    private final OpenApiAppMapper appMapper;
    private final OpenApiCallbackLogMapper logMapper;

    public OpenApiCallbackDeliveryService(OpenApiAppMapper appMapper,
                                          OpenApiCallbackLogMapper logMapper) {
        this.appMapper = appMapper;
        this.logMapper = logMapper;
    }

    /**
     * 投递记录查询（失败可查）：应用只能读到自身 appId 的记录；
     * 过滤条件全部可选，逐列等值匹配。
     */
    public List<OpenApiCallbackLog> records(String appId, String bizRef, String event, String status) {
        LambdaQueryWrapper<OpenApiCallbackLog> query = new LambdaQueryWrapper<OpenApiCallbackLog>()
                .eq(OpenApiCallbackLog::getAppId, appId)
                .eq(OpenApiCallbackLog::getDeleted, 0)
                .orderByDesc(OpenApiCallbackLog::getId);
        if (bizRef != null && !bizRef.isBlank()) {
            query.eq(OpenApiCallbackLog::getBizRef, bizRef);
        }
        if (event != null && !event.isBlank()) {
            query.eq(OpenApiCallbackLog::getEvent, event);
        }
        if (status != null && !status.isBlank()) {
            query.eq(OpenApiCallbackLog::getStatus, status);
        }
        return logMapper.selectList(query);
    }

    /**
     * 恢复重发：同一 (app, event, bizRef) 已成功投递则直接去重返回（零新增）；
     * 否则以与事件监听完全相同的签名/重试/落账路径补发一次。
     * 仅接受实例终态事件；流程动作不被重发改写（投递只读实例终态）。
     */
    public Map<String, Object> resend(String appId, BpmNotifyTrigger trigger, String bizRef) {
        OpenApiApp app = appMapper.selectOne(new LambdaQueryWrapper<OpenApiApp>()
                .eq(OpenApiApp::getAppId, appId)
                .eq(OpenApiApp::getStatus, "ENABLED")
                .eq(OpenApiApp::getDeleted, 0));
        if (app == null) {
            throw new com.sw.ck.common.exception.BaseException(OpenApiErrorCode.APP_NOT_FOUND);
        }
        long deliveredBefore = countSuccess(appId, trigger, bizRef);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", trigger.name());
        result.put("processInstanceId", bizRef);
        if (deliveredBefore > 0) {
            result.put("deduped", true);
            result.put("delivered", false);
            return result;
        }
        dispatch(app, new BpmNotifyEvent(trigger, null, app.getTenantId(), null, bizRef));
        result.put("deduped", false);
        result.put("delivered", countSuccess(appId, trigger, bizRef) > 0);
        return result;
    }

    /** 事件监听转发入口：与恢复重发共用同一投递路径。 */
    public void dispatch(OpenApiApp app, BpmNotifyEvent event) {
        String dedupeKey = app.getAppId() + ":" + event.getTrigger() + ":" + event.getBizId();
        long delivered = countSuccess(app.getAppId(), event.getTrigger(), event.getBizId());
        if (delivered > 0) {
            return; // 去重：同一事件只产生一次成功回调
        }
        // 敏感字段过滤：白名单载荷
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event.getTrigger().name());
        payload.put("processInstanceId", event.getBizId());
        payload.put("tenantId", event.getTenantId());
        String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("回调载荷序列化失败: bizRef={}", event.getBizId());
            return;
        }
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        // 回调密钥以摘要入库；签名以摘要为共享密钥（与入站同一存储口径）
        String secret = app.getCallbackSecretHash() == null
                ? app.getSecretHash() : app.getCallbackSecretHash();
        String signature = OpenApiAuthService.sign(secret, app.getAppId(), timestamp, nonce, body);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = HttpRequest.post(app.getCallbackUrl())
                        .header("X-App-Id", app.getAppId())
                        .header("X-Callback-Timestamp", timestamp)
                        .header("X-Callback-Nonce", nonce)
                        .header("X-Callback-Signature", signature)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .timeout(5000)
                        .execute()
                        .body();
                record(app, event, attempt, "SUCCESS", summarize(response));
                return;
            } catch (RuntimeException e) {
                // P61：HTTP 客户端异常原文（DNS 主机、TLS 细节、内网地址）只进日志；
                // 回调记录对外可见，落结构化、可分类的摘要。
                log.warn("回调投递失败: app={}, bizRef={}, attempt={}, detail={}",
                        app.getAppId(), event.getBizId(), attempt, e.getMessage(), e);
                record(app, event, attempt, "FAILED", callbackFailureSummary(e));
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(500L * attempt); // 线性退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.warn("回调最终失败（失败可查，不回滚流程动作）: app={}, bizRef={}",
                app.getAppId(), event.getBizId());
    }

    /**
     * 该 (应用, 事件, 业务对象) 是否已成功投递（Phase 4：恢复调度据此关闭任务，不重复投递）。
     */
    public boolean isDelivered(String appId, BpmNotifyTrigger trigger, String bizRef) {
        return countSuccess(appId, trigger, bizRef) > 0;
    }

    private long countSuccess(String appId, BpmNotifyTrigger trigger, String bizRef) {
        return logMapper.selectCount(new LambdaQueryWrapper<OpenApiCallbackLog>()
                .eq(OpenApiCallbackLog::getAppId, appId)
                .eq(OpenApiCallbackLog::getBizRef, bizRef)
                .eq(OpenApiCallbackLog::getEvent, trigger.name())
                .eq(OpenApiCallbackLog::getStatus, "SUCCESS")
                .eq(OpenApiCallbackLog::getDeleted, 0));
    }

    private void record(OpenApiApp app, BpmNotifyEvent event, int attempt, String status,
                        String summary) {
        OpenApiCallbackLog row = new OpenApiCallbackLog();
        row.setAppId(app.getAppId());
        row.setEvent(event.getTrigger().name());
        row.setBizRef(event.getBizId());
        row.setUrl(app.getCallbackUrl());
        row.setAttempt(attempt);
        row.setStatus(status);
        row.setResponseSummary(summary == null || summary.length() <= 500
                ? summary : summary.substring(0, 500));
        row.setDeliveredAt(LocalDateTime.now());
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        row.setDeleted(0);
        logMapper.insert(row);
    }

    private static String summarize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 回调失败的对外摘要（P61 §3.2）。
     * <p>开放 API 开发者可得到失败类别与可执行建议，但不得得到 HTTP 客户端原文
     * 或服务端栈。</p>
     */
    private static String callbackFailureSummary(RuntimeException e) {
        String type = e.getClass().getSimpleName();
        boolean connectionLevel = type.contains("Timeout") || type.contains("UnknownHost")
                || type.contains("Connect");
        String category = (connectionLevel
                ? com.sw.ck.common.exception.FailureCategory.RETRYABLE_INFRASTRUCTURE
                : com.sw.ck.common.exception.FailureCategory.SYSTEM_FAULT).name();
        return "category=" + category
                + "; 回调地址不可达或返回非 2xx，请检查回调地址与对端可用性";
    }
}
