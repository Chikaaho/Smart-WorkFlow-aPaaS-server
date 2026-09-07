package com.sw.ck.notify.service;

import com.sw.ck.common.service.BaseService;
import com.sw.ck.notify.dto.NotifyBatchSendReq;
import com.sw.ck.notify.entity.NotifyMessage;

import java.util.List;

/**
 * 站内信通知 Service。
 * <p>
 * 查询时租户条件由 {@code TenantLineHandler} 自动注入，
 * 调用方只需手写 {@code recipient_id} 条件。
 * </p>
 */
public interface NotifyMessageService extends BaseService<NotifyMessage> {

    /**
     * 按接收人查询通知列表（无过滤）。
     * <p>
     * 租户条件由拦截器自动追加，本方法只写 recipient_id 条件。
     * </p>
     *
     * @param recipientId 接收人用户 ID
     * @return 通知列表
     */
    List<NotifyMessage> findByRecipient(Long recipientId);

    /** 按租户上下文查找已发送的幂等消息。 */
    NotifyMessage findByIdempotencyKey(String idempotencyKey);

    /**
     * 按接收人查询通知列表，支持已读状态和关键词过滤。
     * <p>
     * 租户条件由拦截器自动追加，本方法只写 recipient_id 条件。
     * </p>
     *
     * @param recipientId 接收人用户 ID
     * @param read        已读状态过滤（null = 不过滤）
     * @param keyword     关键词过滤，匹配标题或内容（null/空 = 不过滤）
     * @return 通知列表
     */
    List<NotifyMessage> findByRecipientWithFilter(Long recipientId, Boolean read, String keyword);

    /**
     * 删除指定接收人的通知（逻辑删除，由 MyBatis-Plus @TableLogic 生效）。
     * <p>
     * 租户条件由拦截器自动追加；调用方须自行校验 recipient 归属。
     * </p>
     *
     * @param id 通知 ID
     */
    void deleteMessage(Long id);

    /**
     * 批量发送站内通知。
     *
     * @param req 批量发送请求
     * @return 最终去重后的接收人数
     */
    int batchSend(NotifyBatchSendReq req);

    /**
     * 解析批量发送接收人数（去重后，不含实际发送）。
     *
     * @param req 批量发送请求
     * @return 去重后的接收人数
     */
    /** 渠道批量投递用：解析并去重后的有效接收人（服务端校验对象有效性）。 */
    java.util.List<Long> resolveRecipientUserIds(NotifyBatchSendReq req);

    int resolveCount(NotifyBatchSendReq req);

    /**
     * 批量保存通知消息（事务原子性）。
     */
    void saveBatchMessages(List<NotifyMessage> messages);

    /**
     * 按部门ID列表查询当前租户内有效用户ID。
     */
    List<Long> findActiveUserIdsByDeptIds(List<Long> deptIds);

    /**
     * 按角色code列表查询当前租户内有效用户ID。
     */
    List<Long> findActiveUserIdsByRoleCodes(List<String> roleCodes);

    /**
     * 按用户ID列表查询当前租户内的有效用户ID（排除停用/已删除/跨租户）。
     */
    List<Long> findValidUserIds(List<Long> userIds);
}
