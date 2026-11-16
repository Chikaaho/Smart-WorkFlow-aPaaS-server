package com.sw.ck.bpm.process.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.bpm.process.dto.BatchActionResultDTO;
import com.sw.ck.bpm.process.dto.BatchActionSummaryDTO;
import com.sw.ck.bpm.process.entity.BpmHandover;
import com.sw.ck.bpm.process.entity.BpmHandoverItem;
import com.sw.ck.bpm.process.service.BpmBatchService;
import com.sw.ck.bpm.process.service.BpmBatchService.BatchItem;
import com.sw.ck.bpm.process.service.BpmHandoverService;
import com.sw.ck.common.response.R;

import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批量审批（I4 §3.5）与流程交接（I4 §3.6）。
 */
@Log4j2
@RestController
@RequestMapping("/workflow")
public class BpmOpsController {

    @Autowired
    private BpmBatchService batchService;
    @Autowired
    private BpmHandoverService handoverService;

    @Data
    public static class BatchActionRequest {
        private List<BatchItem> items;
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
    public R<BatchActionSummaryDTO> batchAction(@RequestBody BatchActionRequest request) {
        List<BatchActionResultDTO> results = batchService.batchAction(request.getItems());
        int success = (int) results.stream().filter(BatchActionResultDTO::isSuccess).count();
        // 计数用 int：全局 Jackson 把 Long 序列化为字符串（防雪花 ID 精度丢失），计数需保持数值类型
        // 同步契约：处理中恒为 0，由服务端显式给出，前端不得臆算（P61 R2c-A）
        BatchActionSummaryDTO summary = new BatchActionSummaryDTO();
        summary.setResults(results);
        summary.setSuccess(success);
        summary.setFailed(results.size() - success);
        summary.setTotal(results.size());
        summary.setProcessing(0);
        return R.ok(summary);
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
