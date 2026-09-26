package com.sw.ck.form.api.facade;

/**
 * 表单提交前校验的结果（跨模块 API 结果类型）。
 * <p>
 * 只表达"校验已执行且通过"这一成功事实；字段级校验失败、表单不存在、
 * 表单未发布、未登录等拒绝继续以既有业务异常表达，不得以上空或伪状态表达。
 * </p>
 */
public enum SubmissionValidationOutcome {

    /** 校验已执行且通过：提交数据满足表单定义、发布状态与字段级规则。 */
    VALID
}
