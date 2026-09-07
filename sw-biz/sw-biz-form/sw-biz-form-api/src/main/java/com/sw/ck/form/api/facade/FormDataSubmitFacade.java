package com.sw.ck.form.api.facade;

import java.util.Map;

/**
 * 表单数据提交 Facade（form-api 定义，form-biz 实现）。
 * <p>
 * 供流程命令消费者（bpm-process）等跨模块调用方以返回值方式提交表单数据；
 * 经 {@code idempotencyKey} 保证同一提交意图不重复落表单数据。
 * 调用方须已还原可信 LoginUserHolder 上下文。
 * </p>
 */
public interface FormDataSubmitFacade {

    /**
     * 提交表单数据（与手动提交同一校验与受理路径）。
     *
     * @param formKey        表单业务标识
     * @param submittedData  提交数据（字段名 → 值）
     * @param idempotencyKey 提交幂等键（同键重复提交返回既有 recordId）
     * @return 主表记录 recordId
     */
    String submit(String formKey, Map<String, Object> submittedData, String idempotencyKey);

    /**
     * 提交表单数据并携带受控的流程发起通道。
     *
     * @param dispatchChannel NORMAL/P0；为空按普通通道处理
     */
    default String submit(String formKey, Map<String, Object> submittedData,
                          String idempotencyKey, String dispatchChannel) {
        return submit(formKey, submittedData, idempotencyKey);
    }

    /**
     * 提交并携带已解析的流程绑定快照。该参数不能由浏览器直接指定，
     * 仅供统一命令消费者在受理时将服务端解析结果贯穿到流程消费端。
     */
    default String submit(String formKey, Map<String, Object> submittedData,
                          String idempotencyKey, String dispatchChannel,
                          String processDefKey) {
        return submit(formKey, submittedData, idempotencyKey, dispatchChannel);
    }

    /**
     * 提交前校验（D3：受理审批命令前执行，失败定位到字段，不受理不落库）。
     * 与 {@link #submit} 共用同一校验实现，失败抛业务异常（含字段级原因）。
     * 调用方须已还原可信 LoginUserHolder 上下文。
     */
    default void validateSubmission(String formKey, Map<String, Object> submittedData) {
        throw new UnsupportedOperationException("validateSubmission not implemented");
    }
}
