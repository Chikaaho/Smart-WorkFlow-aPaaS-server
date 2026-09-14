package com.sw.ck.notify.api;

import lombok.Builder;
import lombok.Value;

/** 统一渠道消息模型。 */
@Value
@Builder
public class NotifySendRequest {
    Long recipientId;
    String title;
    String content;
    NotifyBizType bizType;
    String bizId;
    Long tenantId;
    NotifyChannel channel;
    String idempotencyKey;

    // ==================== I6 扩展：业务稳定身份与投递元数据 ====================

    /** 事件类型（如 TODO_CREATED / PROCESS_APPROVED / SYSTEM）；缺省按 bizType 语义处理 */
    String eventType;

    /** 同一事件类型的业务发生次序（新一轮办理/再次催办为新的稳定发生标识；缺省 1） */
    Long occurrenceNo;

    /** 渲染时固定的模板 ID 与模板版本（历史投递不可被后续模板编辑改写） */
    Long templateId;
    Integer templateVersion;

    /** 受控深链：仅允许对象类型 + 稳定 ID，服务端打开时重新鉴权 */
    String linkType;
    String linkId;
}
