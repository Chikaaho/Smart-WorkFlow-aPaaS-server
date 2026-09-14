package com.sw.ck.notify.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.notify.dto.NotifyRecordSummaryDTO;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(NotifyRecordController.class);

    private final NotifyRecordService notifyRecordService;

    public NotifyRecordController(NotifyRecordService notifyRecordService) {
        this.notifyRecordService = notifyRecordService;
    }

    /** 发送记录分页（状态/接收人/关键字/时间窗筛选）。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('notify:record:view')")
    public R<PageResult<com.sw.ck.notify.dto.NotifyRecordSummaryDTO>> records(PageParam pageParam,
                                                @RequestParam(required = false) String deliveryStatus,
                                                @RequestParam(required = false) Long recipientId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) LocalDateTime timeFrom,
                                                @RequestParam(required = false) LocalDateTime timeTo) {
        return R.ok(notifyRecordService.pageRecords(pageParam, deliveryStatus, recipientId, keyword, timeFrom, timeTo));
    }

    /** 单条记录详情（最小暴露：不含完整正文/联系方式/Provider 原始响应）。 */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('notify:record:view')")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(notifyRecordService.recordDetail(id));
    }

    /** 必要详情（含正文与尝试流水）：独立权限 + 审计；构造请求仍须授权。 */
    @GetMapping("/{id}/detail")
    @PreAuthorize("@ss.hasPermi('notify:record:detail')")
    public R<Map<String, Object>> fullDetail(@PathVariable Long id) {
        log.info("通知记录详情审计: recordId={}, operator={}", id,
                com.sw.ck.security.holder.LoginUserHolder.get() == null ? null
                        : com.sw.ck.security.holder.LoginUserHolder.get().getUserId());
        return R.ok(notifyRecordService.recordFullDetail(id));
    }

    /** 失败重发（并发只受理一次；IN_APP 渠道重投站内内容）。 */
    @PostMapping("/{id}/resend")
    @PreAuthorize("@ss.hasPermi('notify:record:resend')")
    public R<String> resend(@PathVariable Long id) {
        return R.ok(notifyRecordService.resend(id));
    }
}
