package com.sw.ck.openapi.biz.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.result.BpmProcessStatus;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import com.sw.ck.openapi.api.exception.OpenApiErrorCode;
import com.sw.ck.openapi.biz.dto.OpenApiCompleteTaskResultDTO;
import com.sw.ck.openapi.biz.dto.OpenApiStartResultDTO;
import com.sw.ck.openapi.biz.dto.OpenApiStatusDTO;
import com.sw.ck.openapi.biz.entity.OpenApiIdempotency;
import com.sw.ck.openapi.biz.mapper.OpenApiIdempotencyMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 开放接口流程业务（I4 §3.4）。
 * <p>
 * 外部发起复用正式表单提交链（FormDataSubmitFacade：同一校验/版本解析/幂等/事件链），
 * 不直接调用底层启动能力；办理嵌入式任务复用 completeAsUser 的服务端授权。
 * 同一业务键 + 幂等键只产生一次合法业务效果（DB 唯一键权威）。
 * </p>
 */
@Log4j2
@Service
public class OpenApiProcessService {

    @Autowired
    private FormDataSubmitFacade submitFacade;
    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private BpmRuntimeFacade bpmRuntimeFacade;
    @Autowired
    private OpenApiIdempotencyMapper idempotencyMapper;

    /** 外部发起：返回 recordId（业务键），状态经查询端点获取。 */
    public OpenApiStartResultDTO start(OpenApiAuthService.OpenApiAuthContext context,
                                       String formKey, Map<String, Object> formData,
                                       String businessKey, String idempotencyKey) {
        String idemRef = idempotencyLookup(context.appId(), idempotencyKey, businessKey);
        if (idemRef != null) {
            // 幂等命中：同键重复请求返回既有业务对象，不产生第二次效果
            return statusPayload(idemRef, true);
        }
        try {
            // 提交契约恒 present：empty 属契约违背（提交失败以业务异常表达），不静默降级
            String recordId = submitFacade.submit(formKey, formData, idemKey(context, idempotencyKey, businessKey))
                    .orElseThrow(() -> new IllegalStateException("表单提交未返回记录标识"));
            idempotencyRegister(context.appId(), idemKey(context, idempotencyKey, businessKey), recordId);
            OpenApiStartResultDTO result = new OpenApiStartResultDTO();
            result.setRecordId(recordId);
            result.setIdempotentReplay(false);
            return result;
        } catch (DuplicateKeyException e) {
            // 并发同键：以幂等登记为准
            OpenApiIdempotency existing = idempotencyMapper.selectOne(
                    new LambdaQueryWrapper<OpenApiIdempotency>()
                            .eq(OpenApiIdempotency::getAppId, context.appId())
                            .eq(OpenApiIdempotency::getIdemKey,
                                    idemKey(context, idempotencyKey, businessKey)));
            return statusPayload(
                    existing == null ? null : existing.getResultRef(), true);
        }
    }

    /** 状态查询：仅返回应用发起（businessKey=recordId）且同租户的实例状态。 */
    public OpenApiStatusDTO status(OpenApiAuthService.OpenApiAuthContext context,
                                   String processInstanceId) {
        // 业务键缺失或空白 = 该实例对应用不可见（保持原判定与错误码）
        String businessKey = bpmTaskFacade.getBusinessKey(processInstanceId)
                .filter(key -> !key.isBlank())
                .orElseThrow(() -> new BaseException(OpenApiErrorCode.PROCESS_NOT_VISIBLE));
        // 实例不存在（empty）时无变量可比对，与真实租户变量缺失同样跳过租户比对
        Optional<Object> instanceTenant = bpmRuntimeFacade.getProcessVariables(processInstanceId)
                .map(variables -> variables.get("tenantId"));
        if (instanceTenant.isPresent()
                && !String.valueOf(instanceTenant.get()).equals(String.valueOf(context.tenantId()))) {
            throw new BaseException(OpenApiErrorCode.PROCESS_NOT_VISIBLE);
        }
        // 对外 HTTP 状态字段保持原字符串契约：present 用枚举名，empty（实例不存在）映射为原字面量 "NOT_FOUND"
        String status = bpmRuntimeFacade.getProcessInstanceStatus(processInstanceId)
                .map(BpmProcessStatus::name)
                .orElse("NOT_FOUND");
        OpenApiStatusDTO result = new OpenApiStatusDTO();
        result.setProcessInstanceId(processInstanceId);
        result.setBusinessKey(businessKey);
        result.setStatus(status);
        return result;
    }

    /** 嵌入式任务办理：completeAsUser 服务端校验任务归属（assignee/candidate）。 */
    public OpenApiCompleteTaskResultDTO completeTask(OpenApiAuthService.OpenApiAuthContext context,
                                                     String taskId, Map<String, Object> variables) {
        var task = bpmTaskFacade.getTask(taskId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在"));
        // 判定缺失（empty）与判定为 false 一律按无权办理（fail closed），不得放行
        boolean authorized = context.actAsUserId().toString().equals(task.getAssignee())
                || bpmTaskFacade.canHandle(taskId, String.valueOf(context.actAsUserId()))
                        .filter(Boolean::booleanValue)
                        .isPresent();
        if (!authorized) {
            throw new BaseException(OpenApiErrorCode.SCOPE_DENIED.getCode(),
                    "应用绑定用户无权办理该任务");
        }
        bpmTaskFacade.completeAsUser(taskId, String.valueOf(context.actAsUserId()),
                variables == null ? Map.of() : variables);
        OpenApiCompleteTaskResultDTO result = new OpenApiCompleteTaskResultDTO();
        result.setTaskId(taskId);
        result.setCompleted(true);
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

    private static OpenApiStartResultDTO statusPayload(String recordId, boolean replay) {
        OpenApiStartResultDTO result = new OpenApiStartResultDTO();
        result.setRecordId(recordId);
        result.setIdempotentReplay(replay);
        return result;
    }
}
