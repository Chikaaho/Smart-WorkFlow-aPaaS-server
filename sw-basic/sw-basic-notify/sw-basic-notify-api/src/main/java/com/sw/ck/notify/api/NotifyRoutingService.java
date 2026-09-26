package com.sw.ck.notify.api;

import java.util.List;
import java.util.Optional;

/**
 * 统一投递路由（I6）：规则、订阅与渠道启停的唯一裁决入口。
 * <p>定义于 {@code -api}，实现于 {@code -biz}；调用方（如 bpm）仅依赖本接口。</p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 只表达查询上下文缺失，
 * 合法零匹配以 present 的空集合表达，两者不可互换。
 * </p>
 */
public interface NotifyRoutingService {

    /**
     * 事件类型 → 接收人可用渠道序列（IN_APP 保底恒在列）。
     *
     * @param eventType   事件类型
     * @param recipientId 接收人
     * @return present = 渠道序列（IN_APP 保底，渠道被裁剪后仍至少含 IN_APP；零匹配时为空列表）；
     *         empty = 事件类型缺失等查询上下文不足，路由无法裁决
     */
    Optional<List<NotifyChannel>> channelsFor(String eventType, Long recipientId);

    /**
     * 事件是否必须送达（required 规则存在时为 true）。
     *
     * @param eventType 事件类型
     * @return present = 判定结果（true 存在 required 规则 / false 不存在）；
     *         empty = 事件类型缺失，规则无法裁决
     */
    Optional<Boolean> required(String eventType);

    /**
     * 选择该租户该事件/渠道当前已发布的模板快照。
     * <p>找不到合法快照返回 empty，调用方不得用未版本化文案冒充模板发送。</p>
     *
     * @return present = 已发布模板快照；empty = 无合法快照（含上下文缺失）
     */
    default Optional<NotifyTemplateSelection> templateFor(String eventType, NotifyChannel channel, Long tenantId) {
        return Optional.empty();
    }
}
