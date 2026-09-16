package com.sw.ck.common.exception;

/**
 * 业务错误码契约。
 * <p>
 * {@link #getCode()} 是 0.1.0 既有的数值码，保持兼容、本轮不重编号；
 * {@link #getErrorKey()} 是 P61 新增的全局唯一语义标识，用于跨模块消歧、调用方分流
 * 与后续本地化。
 * <p>
 * 数值码存在跨模块重复（认证 2101-2104 与流程 2101-2104 同值不同义、BPM 内部 2415 双占用），
 * errorKey 不存在重复。冲突数值的登记与弃用口径见
 * {@code docs/governance/error-code-catalog.md}；新增错误不得继续复用已登记冲突值。
 */
public interface ErrorCode {

    int getCode();

    String getMessage();

    /**
     * 稳定、全局唯一、带业务命名空间的语义标识，形如 {@code auth.captcha_mismatch}。
     * 不随语言、受众或承载通道变化；新调用方应据此分流，不得再按数值码推断所属模块。
     */
    String getErrorKey();

    /**
     * 失败分类（P61 §3.5）：决定语气与恢复动作的一致性。
     * <p>默认归为 {@link FailureCategory#SYSTEM_FAULT}；通用出口与各模块应按真实语义覆盖，
     * 使「可重试 / 不可重试」不再被写成同一句「稍后重试」。</p>
     */
    default FailureCategory getCategory() {
        return FailureCategory.SYSTEM_FAULT;
    }
}
