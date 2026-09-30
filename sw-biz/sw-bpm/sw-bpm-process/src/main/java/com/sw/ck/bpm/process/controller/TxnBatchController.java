package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台批量受控动作调用入口（P62 分级执行 S3）。
 * <p>
 * 受理与调用同权限（{@code form:action:invoke}，与动作受控 Port 同口径）；
 * 回查按登录租户边界返回批次与逐项结果（U07 批量项独立追踪）。
 * 受理为异步语义：受理成功仅代表批次已持久化并入队，结果按批次键回查。
 * </p>
 */
@RestController
@RequestMapping("/workflow/txn-batch")
public class TxnBatchController {

    private final TxnBatchService txnBatchService;

    public TxnBatchController(TxnBatchService txnBatchService) {
        this.txnBatchService = txnBatchService;
    }

    /** 受理批量调用（同租户批次键幂等：重放返回原批次）。 */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<TxnBatchView> submit(@RequestBody TxnBatchSubmitRequest request) {
        return R.ok(txnBatchService.submit(request));
    }

    /** 批次回查：批次状态 + 逐项结果。 */
    @GetMapping("/{batchKey}")
    @PreAuthorize("@ss.hasPermi('form:action:invoke')")
    public R<TxnBatchView> get(@PathVariable String batchKey) {
        return R.ok(txnBatchService.get(batchKey));
    }
}
