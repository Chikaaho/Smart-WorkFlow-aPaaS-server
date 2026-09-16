package com.sw.ck.notify.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量发送站内通知响应（P61 §3.5：总数 / 成功 / 失败 / 处理中 + 安全逐项失败明细）。
 *
 * <p><b>兼容</b>：{@link #recipientCount} 继续存在且语义不变（成功投递的接收人数），
 * 旧调用方零改动。</p>
 *
 * <p><b>计数勾稽</b>：{@code phase=SEND_RESULT} 时恒有
 * {@code totalCount == successCount + failureCount + processingCount}，由服务端一次
 * 判定得出，不由客户端按行数推算。{@code phase=RESOLVE} 是发送前的接收人投影，
 * 只有 {@code recipientCount}/{@code totalCount} 有意义，三个结果计数为 0。</p>
 *
 * <p>{@code processingCount} 在同步原子契约（IN_APP 事务落库）下恒为 0，
 * 由展示层显式呈现该事实，不用 0 冒充未知。</p>
 */
@Data
@NoArgsConstructor
public class NotifyBatchSendResp {

    /** 响应阶段：RESOLVE = 发送前接收人投影；SEND_RESULT = 一次批量发送的实际结果。 */
    public static final String PHASE_RESOLVE = "RESOLVE";
    public static final String PHASE_SEND_RESULT = "SEND_RESULT";

    /** 成功投递的接收人数（兼容字段，等于 successCount）。 */
    private int recipientCount;

    /** 响应阶段，取值 {@link #PHASE_RESOLVE} / {@link #PHASE_SEND_RESULT}。 */
    private String phase;

    /** 本次批次判定涉及的接收对象总数（去重后）。 */
    private int totalCount;

    /** 成功数。 */
    private int successCount;

    /** 失败数。 */
    private int failureCount;

    /** 处理中数（同步契约恒 0）。 */
    private int processingCount;

    /** 安全逐项失败明细；无失败时为空列表，不为 null。 */
    private List<NotifyBatchItemFailure> failures;

    /** 兼容构造：只给成功接收人数。 */
    public NotifyBatchSendResp(int recipientCount) {
        this.recipientCount = recipientCount;
        this.successCount = recipientCount;
        this.failures = List.of();
    }

    /** 发送前的接收人投影：只有总数有意义，结果计数保持 0 而不是假装已成功。 */
    public static NotifyBatchSendResp resolve(int recipientCount) {
        NotifyBatchSendResp resp = new NotifyBatchSendResp(recipientCount);
        resp.phase = PHASE_RESOLVE;
        resp.totalCount = recipientCount;
        resp.successCount = 0;
        return resp;
    }

    /** 一次批量发送的实际结果。 */
    public static NotifyBatchSendResp sendResult(int successCount, int processingCount,
                                                 List<NotifyBatchItemFailure> failures) {
        List<NotifyBatchItemFailure> safeFailures = failures == null ? List.of() : List.copyOf(failures);
        NotifyBatchSendResp resp = new NotifyBatchSendResp(successCount);
        resp.phase = PHASE_SEND_RESULT;
        resp.failureCount = safeFailures.size();
        resp.processingCount = processingCount;
        resp.totalCount = successCount + resp.failureCount + processingCount;
        resp.failures = safeFailures;
        return resp;
    }
}
