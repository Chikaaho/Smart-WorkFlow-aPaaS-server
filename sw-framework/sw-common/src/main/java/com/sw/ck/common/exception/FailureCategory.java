package com.sw.ck.common.exception;

import lombok.Getter;

/**
 * 失败分类（P61 §3.5）：错误消息至少归入九类，每类有一致的标题、语气与恢复动作。
 *
 * <p>分类是**文案与恢复语义的单一权威**，不是新的机器分流失量——机器分流仍用
 * {@link ErrorCode#getErrorKey()}。本枚举解决的是「同一类失败在不同页面说法不一致」
 * 与「可重试 / 不可重试被写成同一句『稍后重试』」两类问题。</p>
 *
 * <p>使用纪律：</p>
 * <ul>
 *   <li>可重试类必须说明何时或如何重试（{@link #defaultMessage} 已含该动作）。</li>
 *   <li>不可重试的配置 / 权限 / 数据问题必须指出应改配置、返回上一步或联系哪类管理员，
 *       不得写「请稍后重试」。</li>
 *   <li>成功、处理中与失败三类语义互不混用。</li>
 *   <li>{@link #defaultMessage} 是中文默认文案（zh-CN）；双语阶段由消息目录按语言解析，
 *       本枚举保留为缺省回退值。</li>
 * </ul>
 */
@Getter
public enum FailureCategory {

    /** 输入可修正：用户改一下就能继续。 */
    INPUT_CORRECTABLE(
            "提交的内容有误，请按提示修改后重试",
            false,
            "按提示修正输入"),

    /** 认证失效：需要重新登录或重新校验身份。 */
    AUTHENTICATION_REQUIRED(
            "登录状态已失效，请重新登录",
            false,
            "重新登录"),

    /** 权限不足：不可重试，需要授权。 */
    PERMISSION_DENIED(
            "您没有执行该操作的权限，请联系管理员分配相应权限",
            false,
            "联系管理员授权"),

    /** 对象不存在：可能是被他人删除或链接失效。 */
    OBJECT_NOT_FOUND(
            "该对象不存在或已被删除，请返回列表刷新后重试",
            false,
            "返回列表刷新"),

    /** 业务冲突：状态已变化或许是并发修改，刷新后可重试。 */
    BUSINESS_CONFLICT(
            "数据已被其他操作改变，请刷新后重试",
            true,
            "刷新后重试"),

    /** 处理中：请求已受理，结果稍后可查。 */
    IN_PROGRESS(
            "已提交处理，请稍后在列表中查看结果",
            false,
            "稍后在列表查看结果"),

    /** 可重试基础设施失败：依赖暂时不可用。 */
    RETRYABLE_INFRASTRUCTURE(
            "服务暂时不可用，请稍后重试；若持续出现请提供事件引用联系管理员",
            true,
            "稍后重试"),

    /** 不可重试配置失败：必须由管理员修正配置或由用户换路径。 */
    NON_RETRYABLE_CONFIGURATION(
            "该功能尚未正确配置，请先完成配置或联系管理员处理",
            false,
            "完成配置或联系管理员"),

    /** 系统故障：非用户可修正。 */
    SYSTEM_FAULT(
            "系统暂时无法处理该请求，请稍后重试；若持续出现请提供事件引用联系管理员",
            true,
            "稍后重试并附带事件引用");

    private final String defaultMessage;
    private final boolean retryable;
    private final String recoveryHint;

    FailureCategory(String defaultMessage, boolean retryable, String recoveryHint) {
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
        this.recoveryHint = recoveryHint;
    }
}
