package com.sw.ck.form.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表单受控集成调用结果。
 * <p>
 * {@code result} 为外部能力实际响应：JSON 响应按结构化值返回，非 JSON 文本按字符串返回；
 * {@code replayed} 仅在写副作用命中既有成功账本时为 true。
 * </p>
 */
@Data
@AllArgsConstructor
public class FormIntegrationInvokeResult {

    private String capabilityId;

    private String effectScope;

    private boolean replayed;

    private Object result;
}
