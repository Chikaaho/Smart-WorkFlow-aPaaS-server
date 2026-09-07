package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.CopyItemDTO;
import com.sw.ck.bpm.process.service.BpmCopyQueryService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/** 抄送我的控制器（v0.0.2 OA 个人办理，只读；不授予审批操作权）。 */
@RestController
@RequestMapping("/workflow/my/copies")
public class BpmCopyController {

    private final BpmCopyQueryService copyQueryService;

    public BpmCopyController(BpmCopyQueryService copyQueryService) {
        this.copyQueryService = copyQueryService;
    }

    /** 仅查询本人收到的抄送（关键字 + 时间窗过滤，稳定分页，同事件去重）。 */
    @GetMapping
    public R<PageResult<CopyItemDTO>> myCopies(PageParam pageParam,
                                               @RequestParam(required = false) String processInstanceId,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) LocalDateTime timeFrom,
                                               @RequestParam(required = false) LocalDateTime timeTo) {
        return R.ok(copyQueryService.myCopies(processInstanceId, keyword, timeFrom, timeTo, pageParam));
    }

    /** 抄送详情（仅接收人本人；只读表单快照 + 审批进度/意见，无审批操作权）。 */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(copyQueryService.myCopyDetail(id));
    }
}
