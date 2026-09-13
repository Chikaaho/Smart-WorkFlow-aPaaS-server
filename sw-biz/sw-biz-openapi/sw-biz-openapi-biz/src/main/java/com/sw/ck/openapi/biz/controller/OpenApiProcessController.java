package com.sw.ck.openapi.biz.controller;

import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.common.response.R;
import com.sw.ck.openapi.biz.service.OpenApiAuthService;
import com.sw.ck.openapi.biz.service.OpenApiCallbackDeliveryService;
import com.sw.ck.openapi.biz.service.OpenApiProcessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 流程专用开放接口（I4 §3.4）。
 * <p>
 * 路径走 security permit-urls，鉴权由 {@link OpenApiAuthService} 显式执行：
 * 签名 + 时间窗 + 防重放 + scope + 租户边界；鉴权失败零业务效果。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/openapi/v1")
public class OpenApiProcessController {

    /** 实例终态事件（与 OpenApiCallbackListener 转发口径一致）。 */
    private static final Set<BpmNotifyTrigger> TERMINAL_TRIGGERS = Set.of(
            BpmNotifyTrigger.PROCESS_APPROVED, BpmNotifyTrigger.PROCESS_REJECTED,
            BpmNotifyTrigger.PROCESS_DISAPPROVED, BpmNotifyTrigger.PROCESS_WITHDRAWN,
            BpmNotifyTrigger.PROCESS_DISCARDED);

    private final OpenApiAuthService authService;
    private final OpenApiProcessService processService;
    private final OpenApiCallbackDeliveryService deliveryService;

    public OpenApiProcessController(OpenApiAuthService authService,
                                    OpenApiProcessService processService,
                                    OpenApiCallbackDeliveryService deliveryService) {
        this.authService = authService;
        this.processService = processService;
        this.deliveryService = deliveryService;
    }

    /** 外部发起：经正式表单提交链（校验/版本/幂等/事件），不直接调用底层启动能力。签名材料=原始请求体字节。 */
    @PostMapping("/processes")
    public R<Map<String, Object>> start(
            @RequestHeader(value = "X-App-Id", required = false) String appId,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        OpenApiAuthService.OpenApiAuthContext context = authService.authenticate(
                appId, timestamp, nonce, signature, rawBody == null ? "" : rawBody, "PROCESS_START");
        Map<String, Object> request = parse(rawBody);
        try {
            String formKey = String.valueOf(request.get("formKey"));
            @SuppressWarnings("unchecked")
            Map<String, Object> formData = request.get("formData") instanceof Map<?, ?> raw
                    ? (Map<String, Object>) raw : Map.of();
            String businessKey = request.get("businessKey") == null
                    ? null : String.valueOf(request.get("businessKey"));
            String idempotencyKey = request.get("idempotencyKey") == null
                    ? null : String.valueOf(request.get("idempotencyKey"));
            log.info("开放接口外部发起: appId={}, formKey={}", appId, formKey);
            return R.ok(processService.start(context, formKey, formData, businessKey, idempotencyKey));
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    /** 外部状态查询。 */
    @GetMapping("/processes/{processInstanceId}")
    public R<Map<String, Object>> status(
            @RequestHeader(value = "X-App-Id", required = false) String appId,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @PathVariable String processInstanceId) {
        OpenApiAuthService.OpenApiAuthContext context = authService.authenticate(
                appId, timestamp, nonce, signature, "", "PROCESS_QUERY");
        try {
            return R.ok(processService.status(context, processInstanceId));
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    /** 嵌入式任务办理：服务端校验应用绑定用户的任务归属。签名材料=原始请求体字节。 */
    @PostMapping("/tasks/{taskId}/complete")
    public R<Map<String, Object>> completeTask(
            @RequestHeader(value = "X-App-Id", required = false) String appId,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @PathVariable String taskId,
            @RequestBody(required = false) String rawBody) {
        OpenApiAuthService.OpenApiAuthContext context = authService.authenticate(
                appId, timestamp, nonce, signature, rawBody == null ? "" : rawBody, "TASK_HANDLE");
        Map<String, Object> request = parse(rawBody);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> variables = request != null
                    && request.get("variables") instanceof Map<?, ?> raw
                    ? (Map<String, Object>) raw : Map.of();
            return R.ok(processService.completeTask(context, taskId, variables));
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    /** 出站回调投递记录查询（失败可查，I4 §3.4）：仅本应用自身记录，scope=PROCESS_QUERY。 */
    @GetMapping("/callbacks")
    public R<Map<String, Object>> callbacks(
            @RequestHeader(value = "X-App-Id", required = false) String appId,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestParam(value = "bizRef", required = false) String bizRef,
            @RequestParam(value = "event", required = false) String event,
            @RequestParam(value = "status", required = false) String status) {
        OpenApiAuthService.OpenApiAuthContext context = authService.authenticate(
                appId, timestamp, nonce, signature, "", "PROCESS_QUERY");
        try {
            return R.ok(Map.of("records",
                    deliveryService.records(context.appId(), bizRef, event, status)));
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    /**
     * 出站回调恢复重发（接收端恢复后补投一次）：仅接受实例终态事件；
     * 已成功投递的事件按去重口径零新增返回。scope=PROCESS_QUERY（应用自助补投自身事件）。
     */
    @PostMapping("/callbacks/resend")
    public R<Map<String, Object>> resend(
            @RequestHeader(value = "X-App-Id", required = false) String appId,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        OpenApiAuthService.OpenApiAuthContext context = authService.authenticate(
                appId, timestamp, nonce, signature, rawBody == null ? "" : rawBody, "PROCESS_QUERY");
        try {
            Map<String, Object> request = parse(rawBody);
            String eventName = request.get("event") == null ? null : String.valueOf(request.get("event"));
            String bizRef = request.get("processInstanceId") == null
                    ? null : String.valueOf(request.get("processInstanceId"));
            if (eventName == null || bizRef == null || bizRef.isBlank()) {
                throw new com.sw.ck.common.exception.BaseException(
                        com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode(),
                        "event 与 processInstanceId 不能为空");
            }
            BpmNotifyTrigger trigger;
            try {
                trigger = BpmNotifyTrigger.valueOf(eventName);
            } catch (IllegalArgumentException e) {
                throw new com.sw.ck.common.exception.BaseException(
                        com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode(),
                        "未知事件类型: " + eventName);
            }
            if (!TERMINAL_TRIGGERS.contains(trigger)) {
                throw new com.sw.ck.common.exception.BaseException(
                        com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode(),
                        "仅实例终态事件支持重发");
            }
            return R.ok(deliveryService.resend(context.appId(), trigger, bizRef));
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new com.sw.ck.common.exception.BaseException(
                    com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR.getCode(), "请求体不是合法 JSON");
        }
    }
}
