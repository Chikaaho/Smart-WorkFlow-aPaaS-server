package com.sw.ck.notify.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.dto.NotifyBatchFailureCategory;
import com.sw.ck.notify.dto.NotifyBatchItemFailure;
import com.sw.ck.notify.dto.NotifyBatchSendReq;
import com.sw.ck.notify.dto.NotifyBatchSendResp;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 通知消息接收侧控制器（M05 Step 3 — 第四环闭合）。
 * <p>
 * 提供以下接口：
 * <ul>
 *   <li>{@code GET /notify/messages} — 当前用户的未读/已读通知列表，支持已读状态和关键词过滤</li>
 *   <li>{@code POST /notify/messages/{id}/read} — 标记已读（带越权校验）</li>
 *   <li>{@code DELETE /notify/messages/{id} = 删除通知（带越权校验，逻辑删除）</li>
 * </ul>
 * </p>
 *
 * <h3>鉴权</h3>
 * 所有接口均需登录（默认走过滤器链，无需加 permit 白名单）。
 *
 * <h3>越权</h3>
 * {@code read} / {@code delete} 前置校验消息 {@code recipient_id} 必须等于当前用户 ID，
 * 不满足抛 {@link BaseException(FORBIDDEN)} 拒绝。租户条件由
 * {@code TenantLineHandler} 在 SQL 层自动隔离。
 */
@RestController
@RequestMapping("/notify/messages")
public class NotifyController {

    private static final Logger log = LoggerFactory.getLogger(NotifyController.class);

    private final NotifyMessageService notifyMessageService;
    private final NotifyFacade notifyFacade;

    public NotifyController(NotifyMessageService notifyMessageService, NotifyFacade notifyFacade) {
        this.notifyMessageService = notifyMessageService;
        this.notifyFacade = notifyFacade;
    }

    /**
     * 当前用户的通知列表（支持过滤）。
     * <p>
     * 按 {@code recipient_id = 当前 userId} 查询，结果按创建时间倒序。
     * 租户条件由 {@code TenantLineHandler} 自动注入，不手写 tenant 条件。
     * </p>
     *
     * @param read    已读状态过滤（可选，null = 不过滤；true = 仅已读；false = 仅未读）
     * @param keyword 关键词过滤（可选，匹配标题或内容）
     * @return 通知列表（可能为空）
     */
    @GetMapping
    public R<List<NotifyMessage>> messages(
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false) String keyword) {
        LoginUser loginUser = LoginUserHolder.get();
        Long currentUserId = loginUser.getUserId();

        // 租户条件由 TenantLineHandler 自动注入，只手写 recipient_id 条件
        List<NotifyMessage> list = notifyMessageService.findByRecipientWithFilter(
                currentUserId, read, keyword);

        log.debug("通知列表查询: userId={}, read={}, keyword={}, count={}",
                currentUserId, read, keyword, list.size());
        return R.ok(list);
    }

    /**
     * 标记通知为已读。
     * <p>
     * 前置越权校验：消息的 {@code recipient_id} 必须等于当前用户 ID。
     * 租户条件由 {@code TenantLineHandler} 在 SQL 层自动隔离（查不到等同于 "够不到"）。
     * </p>
     *
     * @param id 通知 ID
     * @return 操作成功
     * @throws BaseException 通知不存在 / 越权时抛出
     */
    @PostMapping("/{id}/read")
    public R<Void> read(@PathVariable Long id) {
        LoginUser loginUser = LoginUserHolder.get();
        Long currentUserId = loginUser.getUserId();

        // 查询消息（租户条件由 TenantLineHandler 自动注入）
        NotifyMessage msg = notifyMessageService.getById(id);
        if (msg == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "通知不存在");
        }

        // 越权校验：recipient 归属（同租户下，只能操作自己的通知）
        if (!currentUserId.equals(msg.getRecipientId())) {
            log.warn("越权拒绝（通知收件人不匹配）: msgId={}, msgRecipientId={}, currentUserId={}",
                    id, msg.getRecipientId(), currentUserId);
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权操作该通知");
        }

        msg.setRead(true);
        notifyMessageService.updateById(msg);
        log.debug("通知已标记已读: id={}, recipientId={}", id, currentUserId);

        return R.ok();
    }

    /**
     * 删除通知（逻辑删除）。
     * <p>
     * 前置越权校验：消息的 {@code recipient_id} 必须等于当前用户 ID，
     * 即只允许删除自己收件箱中的通知。
     * 租户条件由 {@code TenantLineHandler} 在 SQL 层自动隔离。
     * </p>
     *
     * @param id 通知 ID
     * @return 操作成功
     * @throws BaseException 通知不存在 / 越权时抛出
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        LoginUser loginUser = LoginUserHolder.get();
        Long currentUserId = loginUser.getUserId();

        // 查询消息（租户条件由 TenantLineHandler 自动注入）
        NotifyMessage msg = notifyMessageService.getById(id);
        if (msg == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "通知不存在");
        }

        // 越权校验：recipient 归属（同租户下，只能删除自己的通知）
        if (!currentUserId.equals(msg.getRecipientId())) {
            log.warn("越权拒绝（通知收件人不匹配）: msgId={}, msgRecipientId={}, currentUserId={}",
                    id, msg.getRecipientId(), currentUserId);
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权操作该通知");
        }

        notifyMessageService.deleteMessage(id);
        log.debug("通知已删除: id={}, recipientId={}", id, currentUserId);

        return R.ok();
    }

    /**
     * 批量发送站内通知。
     * <p>
     * 支持按用户ID、部门ID、角色code组合选择接收对象，服务端去重后统一投递。
     * 直接内容模式（title+content）与模板模式（templateCode+variables）互斥。
     * 零有效接收人或超过500人上限时整体拒绝。
     * </p>
     */
    @PostMapping("/batch-send")
    @PreAuthorize("@ss.hasPermi('notify:batch:send')")
    public R<NotifyBatchSendResp> batchSend(@RequestBody NotifyBatchSendReq req) {
        // 渠道批量（v0.0.2 R7）：请求指定非 IN_APP 渠道时逐接收人经统一 Facade 投递，
        // 每接收人独立结果与尝试流水（失败子记录可查可重发），幂等键按批次+接收人隔离；
        // 缺省仍走既有 IN_APP 批量入口，保留其幂等边界不变。
        if (req != null && req.getChannel() != null && !req.getChannel().isBlank()
                && !"IN_APP".equalsIgnoreCase(req.getChannel().trim())) {
            return R.ok(batchSendByChannel(req));
        }
        return R.ok(notifyMessageService.batchSendWithOutcome(req));
    }

    /**
     * 非 IN_APP 渠道批量：逐接收人独立投递，按结果分类汇总四项计数与安全失败明细。
     *
     * <p>逐项语义（P61 §3.5）：成功记成功；超时属可重试基础设施失败，已写入下次重试时间
     * 由投递恢复调度接管，计为「处理中」而非失败；其余失败按是否可重试归类，配置类
     * 失败不诱导用户重复操作。失败明细只带分类与本地化结论，不含第三方原文。</p>
     */
    private NotifyBatchSendResp batchSendByChannel(NotifyBatchSendReq req) {
        NotifyChannel channel;
        try {
            channel = NotifyChannel.valueOf(req.getChannel().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "未知渠道: " + req.getChannel());
        }
        int count = notifyMessageService.resolveCount(req);
        var loginUser = LoginUserHolder.get();
        String batchKey = java.util.UUID.randomUUID().toString();
        int delivered = 0;
        int processing = 0;
        // 与站内信批量共用同一判定：请求中被丢弃的对象进入失败明细，
        // 两条路由的「总量 = 成功 + 失败 + 处理中」对照同一个请求集合。
        List<NotifyBatchItemFailure> failures =
                new java.util.ArrayList<>(notifyMessageService.invalidRecipientFailures(req));
        for (Long recipientId : notifyMessageService.resolveRecipientUserIds(req)) {
            NotifySendResult result = notifyFacade.send(NotifySendRequest.builder()
                    .channel(channel)
                    .recipientId(recipientId)
                    .title(req.getTitle())
                    .content(req.getContent())
                    .bizType(NotifyBizType.SYSTEM)
                    .tenantId(loginUser == null || loginUser.getTenantId() == null
                            ? null : loginUser.getTenantId())
                    .idempotencyKey("batch:" + batchKey + ":" + recipientId)
                    .build());
            String status = result.getStatus() == null ? "FAILED" : result.getStatus();
            switch (status) {
                case "SUCCESS" -> delivered++;
                case "TIMEOUT", "PENDING" -> processing++;
                default -> failures.add(NotifyBatchItemFailure.of(String.valueOf(recipientId),
                        failureCategoryOf(result)));
            }
        }
        log.info("渠道批量发送完成: channel={}, recipients={}, delivered={}, processing={}, failed={}",
                channel, count, delivered, processing, failures.size());
        return NotifyBatchSendResp.sendResult(delivered, processing, failures);
    }

    /** 渠道投递结果 → 安全失败分类；不直出第三方原文。 */
    private String failureCategoryOf(NotifySendResult result) {
        String reason = result.getFailureReason() == null ? "" : result.getFailureReason();
        if (reason.contains("未配置生产渠道适配器") || reason.contains("未配置")) {
            return NotifyBatchFailureCategory.CHANNEL_NOT_CONFIGURED;
        }
        return NotifyBatchFailureCategory.DELIVERY_FAILED;
    }

    /**
     * 解析批量发送接收人数（去重后、不含实际发送）。
     * <p>
     * 供前端二次确认前展示服务端计算的最终人数。权限与发送端点一致。
     * </p>
     */
    @PostMapping("/resolve-count")
    @PreAuthorize("@ss.hasPermi('notify:batch:send')")
    public R<NotifyBatchSendResp> resolveCount(@RequestBody NotifyBatchSendReq req) {
        int count = notifyMessageService.resolveCount(req);
        return R.ok(NotifyBatchSendResp.resolve(count));
    }
}
