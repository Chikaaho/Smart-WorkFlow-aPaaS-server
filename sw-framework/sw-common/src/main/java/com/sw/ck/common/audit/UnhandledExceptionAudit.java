package com.sw.ck.common.audit;

/**
 * 服务端未处理异常的审计载体（1.0.0 F3-3）。
 * <p>
 * 由 sw-common 的全局异常出口发布、业务侧监听落库，保持框架层不反向依赖业务模块。
 * 只承载安全结论级摘要（异常类型 + 面向受众的 message + 事件引用号），
 * 不携带堆栈与请求体。
 * </p>
 *
 * @param uri           请求路径（不含 context-path）
 * @param method        HTTP 方法
 * @param username      操作人账号（未认证为空）
 * @param userId        操作人 ID（未认证为空）
 * @param tenantId      租户（未知为空）
 * @param exceptionType 异常类简名
 * @param summary       面向运维的安全结论摘要（不含内部细节）
 * @param traceId       事件引用号（eventRef）
 */
public record UnhandledExceptionAudit(
        String uri,
        String method,
        String username,
        Long userId,
        Long tenantId,
        String exceptionType,
        String summary,
        String traceId) {
}
