package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.entity.BpmHandover;
import com.sw.ck.bpm.process.entity.BpmHandoverItem;
import com.sw.ck.bpm.process.service.BpmBatchService;
import com.sw.ck.bpm.process.service.BpmHandoverService;
import com.sw.ck.common.response.R;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 批量审批（I4 §3.5）与流程交接（I4 §3.6）。
 */
@Slf4j
@RestController
@RequestMapping("/workflow")
public class BpmOpsController {

    private final BpmBatchService batchService;
    private final BpmHandoverService handoverService;

    public BpmOpsController(BpmBatchService batchService, BpmHandoverService handoverService) {
        this.batchService = batchService;
        this.handoverService = handoverService;
    }

    @Data
    public static class BatchActionRequest {
        private List<com.sw.ck.bpm.process.service.BpmBatchService.BatchItem> items;
    }

    @Data
    public static class HandoverRequest {
        private Long fromUserId;
        private Long toUserId;
        private List<String> scopeDefKeys;
        private Boolean includeProxyRules;
    }

    /** 批量动作：服务端逐项校验并返回逐项结果（部分执行结果不掩盖）。 */
    @PreAuthorize("@ss.hasPermi('workflow:task:batch')")
    @PostMapping("/tasks/batch-action")
    public R<Map<String, Object>> batchAction(@RequestBody BatchActionRequest request) {
        List<Map<String, Object>> results = batchService.batchAction(request.getItems());
        int success = (int) results.stream().filter(row -> Boolean.TRUE.equals(row.get("success"))).count();
        // 计数用 int：全局 Jackson 把 Long 序列化为字符串（防雪花 ID 精度丢失），计数需保持数值类型
        // 同步契约：处理中恒为 0，由服务端显式给出，前端不得臆算（P61 R2c-A）
        return R.ok(Map.of("results", results, "success", success,
                "failed", results.size() - success, "total", results.size(), "processing", 0));
    }

    @PreAuthorize("@ss.hasPermi('workflow:handover:manage')")
    @PostMapping("/handover")
    public R<BpmHandover> handover(@RequestBody HandoverRequest request) {
        BpmHandover handover = handoverService.handover(request.getFromUserId(),
                request.getToUserId(), request.getScopeDefKeys(),
                Boolean.TRUE.equals(request.getIncludeProxyRules()));
        return R.ok(handover);
    }

    @PreAuthorize("@ss.hasPermi('workflow:handover:manage')")
    @GetMapping("/handover/{id}/items")
    public R<List<BpmHandoverItem>> handoverItems(@PathVariable Long id) {
        return R.ok(handoverService.items(id));
    }
}
