package com.sw.ck.notify.service;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.notify.vo.NotifyRecordDetailVO;
import com.sw.ck.notify.vo.NotifyRecordSummaryVO;
import com.sw.ck.notify.entity.NotifyMessage;

import java.time.LocalDateTime;
import java.util.List;

/** 通知发送记录服务（v0.0.2 OA：有权管理者可见的记录/日志/重发）。 */
public interface NotifyRecordService {

    /** 发送记录分页（状态/接收人/关键字/时间窗筛选）。 */
    PageResult<NotifyMessage> pageEntities(PageParam pageParam, String deliveryStatus, Long recipientId,
                                           String keyword, LocalDateTime timeFrom, LocalDateTime timeTo);

    /** 发送记录分页——最小暴露摘要（不含完整正文/联系方式/Provider 原始响应）。 */
    PageResult<NotifyRecordSummaryVO> pageRecords(PageParam pageParam, String deliveryStatus,
                                                                        Long recipientId, String keyword,
                                                                        LocalDateTime timeFrom, LocalDateTime timeTo);

    /** 单条记录 + 关联尝试流水（原始失败、各次尝试与最新结果）。 */
    NotifyRecordDetailVO recordDetail(Long id);

    /** 必要详情（含完整正文/尝试流水）：仅限独立详情权限调用并记审计。 */
    NotifyRecordDetailVO recordFullDetail(Long id);

    /**
     * 失败重发：仅对明确失败（FAILED）且无进行中重发的记录受理；
     * 并发重发只受理一次；结果与尝试流水落库。返回最新消息状态。
     */
    String resend(Long id);
}
