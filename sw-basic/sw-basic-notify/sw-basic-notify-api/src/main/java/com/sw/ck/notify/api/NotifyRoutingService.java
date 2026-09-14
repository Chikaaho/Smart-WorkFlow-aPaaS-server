package com.sw.ck.notify.api;

import java.util.List;

/**
 * 统一投递路由（I6）：规则、订阅与渠道启停的唯一裁决入口。
 * <p>定义于 {@code -api}，实现于 {@code -biz}；调用方（如 bpm）仅依赖本接口。</p>
 */
public interface NotifyRoutingService {

    /** 事件类型 → 接收人可用渠道序列（IN_APP 保底恒在列）。 */
    List<NotifyChannel> channelsFor(String eventType, Long recipientId);

    /** 事件是否必须送达（required 规则存在时为 true）。 */
    boolean required(String eventType);
}
