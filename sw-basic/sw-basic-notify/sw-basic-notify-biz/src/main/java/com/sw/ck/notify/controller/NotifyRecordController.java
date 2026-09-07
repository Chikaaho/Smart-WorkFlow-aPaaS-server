package com.sw.ck.notify.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.service.NotifyRecordService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知发送记录控制器（v0.0.2 OA，有权管理者可见）。
 * <p>
 * 记录/日志查询逐项检查 {@code notify:record:view}，重发检查 {@code notify:record:resend}；
 * 普通用户收件箱仍走 {@code /notify/messages}，本入口不暴露他人通知内容给无权用户。
 * 租户条件由 TenantLineHandler 自动隔离。
 * </p>
 */
@RestController
@RequestMapping("/notify/records")
public class NotifyRecordController {

    private final NotifyRecordService notifyRecordService;

    public NotifyRecordController(NotifyRecordService notifyRecordService) {
        this.notifyRecordService = notifyRecordService;
    }

    /** 发送记录分页（状态/接收人/关键字/时间窗筛选）。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('notify:record:view')")
    public R<PageResult<NotifyMessage>> records(PageParam pageParam,
                                                @RequestParam(required = false) String deliveryStatus,
                                                @RequestParam(required = false) Long recipientId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) LocalDateTime timeFrom,
                                                @RequestParam(required = false) LocalDateTime timeTo) {
        return R.ok(notifyRecordService.pageRecords(pageParam, deliveryStatus, recipientId, keyword, timeFrom, timeTo));
    }

    /** 单条记录详情 + 关联尝试流水。 */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:record:view')")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(notifyRecordService.recordDetail(id));
    }

    /** 失败重发（并发只受理一次；IN_APP 渠道重投站内内容）。 */
    @PostMapping("/{id}/resend")
    @PreAuthorize("@ss.hasPermi('notify:record:resend')")
    public R<String> resend(@PathVariable Long id) {
        return R.ok(notifyRecordService.resend(id));
    }
}
