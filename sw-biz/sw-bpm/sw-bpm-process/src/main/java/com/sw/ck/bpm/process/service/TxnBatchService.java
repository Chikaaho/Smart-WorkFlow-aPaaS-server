package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;

/**
 * 后台批量受控动作调用（P62 分级执行 S3，U02/U07）。
 * <p>
 * 有界批次（1—500 项）、逐项独立事务、持久结果与稳定项键幂等；
 * 批次重放返回原批次，同项同键异载荷由动作内核明确拒绝，
 * 重复消费不重做已成功项。
 * </p>
 */
public interface TxnBatchService {

    /**
     * 受理批量调用：与受理事务同事务落批次/项行并入队 BATCH_INVOKE 命令。
     * <p>同租户批次键已存在时返回原批次（{@code replay=true}），不重建。</p>
     *
     * @param request 批量受理请求
     * @return 批次视图（含逐项受理状态）
     */
    TxnBatchView submit(TxnBatchSubmitRequest request);

    /**
     * 批次回查：批次 + 逐项结果（U07 批量项独立追踪）。
     *
     * @param batchKey 批次键
     * @return 批次视图
     */
    TxnBatchView get(String batchKey);
}
