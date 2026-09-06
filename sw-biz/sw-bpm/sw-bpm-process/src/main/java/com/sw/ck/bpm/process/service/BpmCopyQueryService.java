package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.CopyItemDTO;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.time.LocalDateTime;
import java.util.Map;

/** 抄送我的查询服务（v0.0.2 OA，只读）。 */
public interface BpmCopyQueryService {

    /**
     * 仅查询本人收到的抄送（强制 recipient=当前用户），支持关键字与时间窗过滤、稳定分页；
     * 同接收人同一抄送事件去重。
     */
    PageResult<CopyItemDTO> myCopies(String keyword, LocalDateTime timeFrom, LocalDateTime timeTo,
                                     PageParam pageParam);

    /**
     * 抄送详情（仅接收人本人）：抄送记录 + 实例摘要 + 只读表单快照（processVariables.formData）
     * + 审批进度与意见。抄送身份不获得审批操作权。
     */
    Map<String, Object> myCopyDetail(Long copyId);
}
