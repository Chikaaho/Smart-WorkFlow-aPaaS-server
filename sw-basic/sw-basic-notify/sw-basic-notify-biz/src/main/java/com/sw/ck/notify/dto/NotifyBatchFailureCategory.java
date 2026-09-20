package com.sw.ck.notify.dto;

/**
 * 批量发送逐项失败的稳定分类（P61 §3.5 九类错误语义在批量场景的收敛）。
 *
 * <p>每个分类有一个稳定 {@code errorKey}，与本地化目录 {@code error.<errorKey>}
 * 对齐；客户端按分类分组、按 errorKey 断言，不依赖文案子串。</p>
 */
public final class NotifyBatchFailureCategory {

    /** 接收对象无效或不可投递（不存在 / 跨租户 / 已停用 / 已删除）。 */
    public static final String RECIPIENT_NOT_DELIVERABLE = "RECIPIENT_NOT_DELIVERABLE";

    /** 渠道未配置生产适配器：配置类不可重试，不诱导用户重复操作。 */
    public static final String CHANNEL_NOT_CONFIGURED = "CHANNEL_NOT_CONFIGURED";

    /** 投递超时：可重试基础设施失败，由投递恢复调度接管。 */
    public static final String DELIVERY_TIMEOUT = "DELIVERY_TIMEOUT";

    /** 其他投递失败（含受控对端返回失败与投递异常的分类摘要）。 */
    public static final String DELIVERY_FAILED = "DELIVERY_FAILED";

    private NotifyBatchFailureCategory() {
    }

    /** 分类 → 本地化语义键。 */
    public static String errorKeyOf(String category) {
        return switch (category == null ? "" : category) {
            case RECIPIENT_NOT_DELIVERABLE -> "notify.batch.recipient_not_deliverable";
            case CHANNEL_NOT_CONFIGURED -> "notify.batch.channel_not_configured";
            case DELIVERY_TIMEOUT -> "notify.batch.delivery_timeout";
            default -> "notify.batch.delivery_failed";
        };
    }

    /** 分类 → zh-CN 缺省文案（本地化目录缺失时的兜底，不构成第二权威）。 */
    public static String defaultMessageOf(String category) {
        return switch (category == null ? "" : category) {
            case RECIPIENT_NOT_DELIVERABLE -> "接收对象无效或不可投递";
            case CHANNEL_NOT_CONFIGURED -> "投递渠道未配置，请联系管理员";
            case DELIVERY_TIMEOUT -> "投递超时，系统将自动重试";
            default -> "投递失败";
        };
    }
}
