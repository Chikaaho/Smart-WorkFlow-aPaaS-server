package com.sw.ck.form.api.port;

import com.sw.ck.form.api.event.FormSubmittedEvent;

/**
 * 流程发起受理 SPI（form-api 定义，bpm-process 实现）。
 * <p>
 * 表单提交事务内调用：实现方必须与调用方同事务持久化受理事实，
 * 保证"表单已落库 ⇒ 流程发起命令可回查、不丢失"。无启用流程绑定时
 * 返回 {@code null}（no-op，不是失败）。
 * </p>
 */
public interface FlowStartPort {

    /**
     * 受理流程发起命令。
     *
     * @param event 表单提交事件（含 formKey/recordId/submitter/tenantId/submittedData）
     * @return 受理标识（命令 ID）；无启用流程绑定时返回 null
     */
    Long acceptFlowStart(FormSubmittedEvent event);
}
