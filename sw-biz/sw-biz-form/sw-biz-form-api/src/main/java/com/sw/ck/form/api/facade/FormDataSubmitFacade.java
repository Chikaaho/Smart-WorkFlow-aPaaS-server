package com.sw.ck.form.api.facade;

import java.util.Map;
import java.util.Optional;

/**
 * 表单数据提交 Facade（form-api 定义，form-biz 实现）。
 * <p>
 * 供流程命令消费者（bpm-process）等跨模块调用方以返回值方式提交表单数据；
 * 经 {@code idempotencyKey} 保证同一提交意图不重复落表单数据。
 * 调用方须已还原可信 LoginUserHolder 上下文。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：提交失败（校验、权限、落库）继续抛明确异常，
 * 不以上空表达失败。
 * </p>
 */
public interface FormDataSubmitFacade {

    /**
     * 提交表单数据（与手动提交同一校验与受理路径）。
     *
     * @param formKey        表单业务标识
     * @param submittedData  提交数据（字段名 → 值）
     * @param idempotencyKey 提交幂等键（同键重复提交返回既有 recordId）
     * @return present = 主表记录 recordId（幂等键命中既有记录时为既有 ID，属合法重复提交）；
     *         当前契约恒 present，提交失败抛业务异常
     */
    Optional<String> submit(String formKey, Map<String, Object> submittedData, String idempotencyKey);

    /**
     * 提交表单数据并携带受控的流程发起通道。
     *
     * @param dispatchChannel NORMAL/P0；为空按普通通道处理
     * @return present = 主表记录 recordId；当前契约恒 present
     */
    default Optional<String> submit(String formKey, Map<String, Object> submittedData,
                                    String idempotencyKey, String dispatchChannel) {
        return submit(formKey, submittedData, idempotencyKey);
    }

    /**
     * 提交并携带已解析的流程绑定快照。该参数不能由浏览器直接指定，
     * 仅供统一命令消费者在受理时将服务端解析结果贯穿到流程消费端。
     *
     * @return present = 主表记录 recordId；当前契约恒 present
     */
    default Optional<String> submit(String formKey, Map<String, Object> submittedData,
                                    String idempotencyKey, String dispatchChannel,
                                    String processDefKey) {
        return submit(formKey, submittedData, idempotencyKey, dispatchChannel);
    }

    /**
     * 提交前校验（D3：受理审批命令前执行，失败定位到字段，不受理不落库）。
     * 与 {@link #submit} 共用同一校验实现，失败抛业务异常（含字段级原因）。
     * 调用方须已还原可信 LoginUserHolder 上下文。
     *
     * @return present = {@link SubmissionValidationOutcome#VALID} 校验已执行且通过；
     *         当前契约恒 present，校验失败以业务异常表达
     * @throws UnsupportedOperationException 实现未提供该校验能力时（保持既有契约）
     */
    default Optional<SubmissionValidationOutcome> validateSubmission(String formKey,
                                                                     Map<String, Object> submittedData) {
        throw new UnsupportedOperationException("validateSubmission not implemented");
    }
}
