package com.sw.ck.common.integration;

/**
 * 受控外部能力描述（表单运行期只读视图）。
 * <p>
 * 目标 URL 与 HTTP 方法由实现侧持有，不通过该描述暴露给表单模块或浏览器；
 * 表单侧只使用稳定能力标识与副作用分类。
 * </p>
 */
public record ExternalCapability(
        String name,
        String description,
        String effectScope,
        int timeoutSeconds,
        Long tenantId) {
}
