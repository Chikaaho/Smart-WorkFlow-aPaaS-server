package com.sw.ck.notify.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.notify.api.NotifyLinkAuthorizer;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内信收件箱控制器（I6）：服务端真分页、未读数、全部已读与受保护深链。
 * <p>收件箱读取同一 {@code sw_notify_message} 的 IN_APP 行；越权由
 * recipient_id 校验 + 租户 SQL 隔离 fail closed。深链只保存对象类型与稳定 ID，
 * 打开时经 {@link NotifyLinkAuthorizer} 重新鉴权。</p>
 */
@RestController
@RequestMapping("/notify/inbox")
public class NotifyInboxController {

    private static final Logger log = LoggerFactory.getLogger(NotifyInboxController.class);

    private final NotifyMessageService notifyMessageService;
    private final org.springframework.beans.factory.ObjectProvider<NotifyLinkAuthorizer> authorizerProvider;

    public NotifyInboxController(NotifyMessageService notifyMessageService,
                                 org.springframework.beans.factory.ObjectProvider<NotifyLinkAuthorizer> authorizerProvider) {
        this.notifyMessageService = notifyMessageService;
        this.authorizerProvider = authorizerProvider;
    }

    /** 收件箱分页（read/eventType/keyword 过滤；稳定排序 create_time desc, id desc）。 */
    @GetMapping
    public R<PageResult<NotifyMessage>> inbox(PageParam pageParam,
                                              @RequestParam(required = false) Boolean read,
                                              @RequestParam(required = false) String eventType,
                                              @RequestParam(required = false) String keyword) {
        return R.ok(notifyMessageService.pageInbox(pageParam, LoginUserHolder.get().getUserId(),
                read, eventType, keyword));
    }

    /** 当前未读数。 */
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notifyMessageService.unreadCount(LoginUserHolder.get().getUserId()));
    }

    /** 全部已读（幂等）。 */
    @PostMapping("/read-all")
    public R<Integer> readAll() {
        return R.ok(notifyMessageService.markAllRead(LoginUserHolder.get().getUserId()));
    }

    /** 受保护深链：返回当前用户对该消息深链的鉴权结果与受控目标标识。 */
    @PostMapping("/{id}/link")
    public R<Map<String, Object>> openLink(@PathVariable Long id) {
        NotifyMessage msg = requireOwn(id);
        if (msg.getLinkType() == null || msg.getLinkId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "该消息没有可跳转对象");
        }
        NotifyLinkAuthorizer authorizer = authorizerProvider.getIfAvailable();
        if (authorizerProvider.getIfAvailable() == null) {
            failClosed(msg);
        } else if (!authorizerProvider.getIfAvailable().canOpen(msg.getLinkType(), msg.getLinkId())) {
            failClosed(msg);
        }
        log.info("深链打开: msgId={}, linkType={}, userId={}", id, msg.getLinkType(),
                LoginUserHolder.get().getUserId());
        return R.ok(Map.of("linkType", msg.getLinkType(), "linkId", msg.getLinkId()));
    }

    private NotifyMessage requireOwn(Long id) {
        NotifyMessage msg = notifyMessageService.getById(id);
        if (msg == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "通知不存在");
        }
        if (!LoginUserHolder.get().getUserId().equals(msg.getRecipientId())) {
            log.warn("收件箱越权拒绝: msgId={}, userId={}", id, LoginUserHolder.get().getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权操作该通知");
        }
        return msg;
    }

    private void failClosed(NotifyMessage msg) {
        log.warn("深链鉴权拒绝: msgId={}, linkType={}, linkId={}, userId={}", msg.getId(),
                msg.getLinkType(), LoginUserHolder.get().getUserId());
        throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权访问该业务对象");
    }
}
