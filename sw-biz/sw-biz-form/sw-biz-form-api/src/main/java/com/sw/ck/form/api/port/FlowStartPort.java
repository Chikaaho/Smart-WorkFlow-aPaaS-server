package com.sw.ck.form.api.port;

import com.sw.ck.form.api.event.FormSubmittedEvent;

import java.util.Optional;

/**
 * 流程发起受理 SPI（form-api 定义，bpm-process 实现）。
 * <p>
 * 表单提交事务内调用：实现方必须与调用方同事务持久化受理事实，
 * 保证"表单已落库 ⇒ 流程发起命令可回查、不丢失"。
 * </p>
 * <p>
 * 无启用流程绑定是合法的"无目标"结论（no-op，不是失败），以 empty 表达；
 * 绑定冲突等真实错误继续抛出明确异常。
 * </p>
 */
public interface FlowStartPort {

    /**
     * 受理流程发起命令。
     *
     * @param event 表单提交事件（含 formKey/recordId/submitter/tenantId/submittedData）
     * @return present = 受理标识（命令 ID；同一提交意图重复受理返回同一 ID）；
     *         empty = 该 formKey 无启用流程绑定，按 no-op 不受理
     */
    Optional<Long> acceptFlowStart(FormSubmittedEvent event);
}
