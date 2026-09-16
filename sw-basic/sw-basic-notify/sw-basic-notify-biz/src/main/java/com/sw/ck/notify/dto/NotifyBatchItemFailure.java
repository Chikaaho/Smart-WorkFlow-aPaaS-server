package com.sw.ck.notify.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量发送的单条失败明细（P61 §3.5：批量操作需可下钻失败明细）。
 *
 * <p>只承载安全结论：没有原始异常、栈帧、SQL、租户秘密或第三方原文。
 * {@code category} 是稳定机器分类，供客户端分组与图标；{@code message} 是
 * 服务端已按请求语言本地化的可读结论，Web 直接展示，不另立文案权威。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyBatchItemFailure {

    /** 接收对象引用：直接指定的用户 ID 原样回显，解析型对象用其标识（部门/角色）。 */
    private String recipientRef;

    /** 稳定分类键，取值见 {@link NotifyBatchFailureCategory}。 */
    private String category;

    /** 同一分类的稳定语义键，随语言变化不影响其值。 */
    private String errorKey;

    /** 按请求语言本地化的安全结论。 */
    private String message;

    /**
     * 由稳定分类构造明细：{@code errorKey} 与 {@code message} 都从分类派生，
     * 调用方不自行拼文案，避免出现第二文案权威。
     */
    public static NotifyBatchItemFailure of(String recipientRef, String category) {
        String errorKey = NotifyBatchFailureCategory.errorKeyOf(category);
        return new NotifyBatchItemFailure(recipientRef, category, errorKey,
                com.sw.ck.common.i18n.LocalizedMessages.text(
                        errorKey, NotifyBatchFailureCategory.defaultMessageOf(category)));
    }
}
