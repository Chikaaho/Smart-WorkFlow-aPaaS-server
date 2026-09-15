package com.sw.ck.openapi.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import com.sw.ck.openapi.api.exception.OpenApiErrorCode;
import com.sw.ck.openapi.biz.entity.OpenApiIdempotency;
import com.sw.ck.openapi.biz.mapper.OpenApiIdempotencyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放接口流程业务（I4 §3.4）。
 * <p>
 * 外部发起复用正式表单提交链（FormDataSubmitFacade：同一校验/版本解析/幂等/事件链），
 * 不直接调用底层启动能力；办理嵌入式任务复用 completeAsUser 的服务端授权。
 * 同一业务键 + 幂等键只产生一次合法业务效果（DB 唯一键权威）。
 * </p>
 */
@Slf4j
@Service
public class OpenApiProcessService {

    private final FormDataSubmitFacade submitFacade;
    private final BpmTaskFacade bpmTaskFacade;
    private final BpmRuntimeFacade bpmRuntimeFacade;
    private final OpenApiIdempotencyMapper idempotencyMapper;

    public OpenApiProcessService(FormDataSubmitFacade submitFacade,
                                 BpmTaskFacade bpmTaskFacade,
                                 BpmRuntimeFacade bpmRuntimeFacade,
                                 OpenApiIdempotencyMapper idempotencyMapper) {
        this.submitFacade = submitFacade;
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmRuntimeFacade = bpmRuntimeFacade;
        this.idempotencyMapper = idempotencyMapper;
    }

    /** 外部发起：返回 recordId（业务键），状态经查询端点获取。 */
    public Map<String, Object> start(OpenApiAuthService.OpenApiAuthContext context,
                                     String formKey, Map<String, Object> formData,
                                     String businessKey, String idempotencyKey) {
        String idemRef = idempotencyLookup(context.appId(), idempotencyKey, businessKey);
        if (idemRef != null) {
            // 幂等命中：同键重复请求返回既有业务对象，不产生第二次效果
            return statusPayload(bpmRuntimeFacade, idemRef, true);
        }
        try {
            String recordId = submitFacade.submit(formKey, formData, idemKey(context, idempotencyKey, businessKey));
            idempotencyRegister(context.appId(), idemKey(context, idempotencyKey, businessKey), recordId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recordId", recordId);
            result.put("idempotentReplay", false);
            return result;
        } catch (DuplicateKeyException e) {
            // 并发同键：以幂等登记为准
            OpenApiIdempotency existing = idempotencyMapper.selectOne(
                    new LambdaQueryWrapper<OpenApiIdempotency>()
                            .eq(OpenApiIdempotency::getAppId, context.appId())
                            .eq(OpenApiIdempotency::getIdemKey,
                                    idemKey(context, idempotencyKey, businessKey)));
            return statusPayload(bpmRuntimeFacade,
                    existing == null ? null : existing.getResultRef(), true);
        }
    }

    /** 状态查询：仅返回应用发起（businessKey=recordId）且同租户的实例状态。 */
    public Map<String, Object> status(OpenApiAuthService.OpenApiAuthContext context,
                                      String processInstanceId) {
        String businessKey = bpmTaskFacade.getBusinessKey(processInstanceId);
        if (businessKey == null || businessKey.isBlank()) {
            throw new BaseException(OpenApiErrorCode.PROCESS_NOT_VISIBLE);
        }
        Map<String, Object> variables = bpmRuntimeFacade.getProcessVariables(processInstanceId);
        Object tenant = variables.get("tenantId");
        if (tenant != null && !String.valueOf(tenant).equals(String.valueOf(context.tenantId()))) {
            throw new BaseException(OpenApiErrorCode.PROCESS_NOT_VISIBLE);
        }
        String status = bpmRuntimeFacade.getProcessInstanceStatus(processInstanceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processInstanceId", processInstanceId);
        result.put("businessKey", businessKey);
        result.put("status", status);
        return result;
    }

    /** 嵌入式任务办理：completeAsUser 服务端校验任务归属（assignee/candidate）。 */
    public Map<String, Object> completeTask(OpenApiAuthService.OpenApiAuthContext context,
                                            String taskId, Map<String, Object> variables) {
        var task = bpmTaskFacade.getTask(taskId);
        if (task == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }
        if (!context.actAsUserId().toString().equals(task.getAssignee())
                && !bpmTaskFacade.canHandle(taskId, String.valueOf(context.actAsUserId()))) {
            throw new BaseException(OpenApiErrorCode.SCOPE_DENIED.getCode(),
                    "应用绑定用户无权办理该任务");
        }
        bpmTaskFacade.completeAsUser(taskId, String.valueOf(context.actAsUserId()),
                variables == null ? Map.of() : variables);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("completed", true);
        return result;
    }

    private String idemKey(OpenApiAuthService.OpenApiAuthContext context, String idempotencyKey,
                           String businessKey) {
        return (idempotencyKey == null || idempotencyKey.isBlank()
                ? "BK:" + businessKey : idempotencyKey) + ":" + context.appId();
    }

    private String idempotencyLookup(String appId, String idempotencyKey, String businessKey) {
        if ((idempotencyKey == null || idempotencyKey.isBlank()) && (businessKey == null || businessKey.isBlank())) {
            return null;
        }
        String key = (idempotencyKey == null || idempotencyKey.isBlank()
                ? "BK:" + businessKey : idempotencyKey) + ":" + appId;
        OpenApiIdempotency row = idempotencyMapper.selectOne(
                new LambdaQueryWrapper<OpenApiIdempotency>()
                        .eq(OpenApiIdempotency::getAppId, appId)
                        .eq(OpenApiIdempotency::getIdemKey, key));
        return row == null ? null : row.getResultRef();
    }

    private void idempotencyRegister(String appId, String key, String resultRef) {
        OpenApiIdempotency row = new OpenApiIdempotency();
        row.setAppId(appId);
        row.setIdemKey(key);
        row.setResultRef(resultRef);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        row.setDeleted(0);
        try {
            idempotencyMapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 并发登记：唯一键兜底，业务效果只发生一次
        }
    }

    private static Map<String, Object> statusPayload(BpmRuntimeFacade facade, String recordId,
                                                     boolean replay) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", recordId);
        result.put("idempotentReplay", replay);
        return result;
    }
}
