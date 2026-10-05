package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.BpmCommandBatch;
import com.sw.ck.bpm.process.entity.BpmCommandBatchItem;
import com.sw.ck.bpm.process.entity.BatchItemStatusEnum;
import com.sw.ck.bpm.process.entity.BatchStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.port.FormTxnActionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 后台批量受控动作调用处理器（P62 分级执行 S3，U02/U03/U07）。
 * <p>
/**
 * 后台批量受控动作调用处理器（P62 分级执行 S3，U02/U03/U07）。
 * <p>
 * 逐项独立事务（{@code REQUIRES_NEW}）：单项业务拒绝不回滚其他项，部分失败可定位、
 * 可恢复。项调用经 {@link FormTxnActionPort} 以稳定幂等键
 * {@code BATCH:{batchKey}:{itemKey}} 执行——同键同载荷重放返回原结果，
 * 同键异载荷被动作内核明确拒绝（1606）；重入恢复只处理 PENDING 项，
 * 已终态项不重做。批次结算（全部项终态）与命令效果权威账本同事务写入。
 * </p>
 * <p>
 * 消费侧隔离（P62 G5a/U06 兼容合同）：新类型处理器仅在部署开关
 * {@code sw.bpm.txn-batch.enabled=true} 时注册；默认（旧版本语义进程）不注册，
 * 与受理侧同一开关保证"旧消费者存活期零新类型"。
 * </p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "sw.bpm.txn-batch.enabled", havingValue = "true", matchIfMissing = false)
public class BatchInvokeCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(BatchInvokeCommandHandler.class);

    private final BpmCommandBatchMapper batchMapper;
    private final BpmCommandBatchItemMapper itemMapper;
    private final BpmCommandQueue commandQueue;
    private final FormTxnActionPort txnActionPort;
    private final CommandEffectRecorder effectRecorder;
    private final TransactionTemplate txTemplate;
    private final com.sw.ck.bpm.process.service.ResourceReleaseService releaseService;
    private final com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService;

    public BatchInvokeCommandHandler(BpmCommandBatchMapper batchMapper,
                                     BpmCommandBatchItemMapper itemMapper,
                                     BpmCommandQueue commandQueue,
                                     FormTxnActionPort txnActionPort,
                                     CommandEffectRecorder effectRecorder,
                                     org.springframework.transaction.PlatformTransactionManager transactionManager,
                                     com.sw.ck.bpm.process.service.ResourceReleaseService releaseService,
                                     com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.commandQueue = commandQueue;
        this.txnActionPort = txnActionPort;
        this.effectRecorder = effectRecorder;
        this.releaseService = releaseService;
        this.admissionService = admissionService;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = template;
    }

    @Override
    public java.util.Set<CommandTypeEnum> types() {
        return java.util.Set.of(CommandTypeEnum.BATCH_INVOKE);
    }

    @Override
    public String handle(CommandEnvelope envelope) {
        long commandId = envelope.getCommandId();
        BpmCommandBatch batch = requireBatch(envelope);
        markProcessing(batch.getId());
        // 释放会计按命令行冻结字段执行（段/租户/策略版本）；受理人身份随信封还原
        com.sw.ck.bpm.process.entity.BpmCommand batchCommand = batchAccountingCommand(envelope);

        List<BpmCommandBatchItem> pending = itemMapper.selectList(
                Wrappers.<BpmCommandBatchItem>lambdaQuery()
                        .eq(BpmCommandBatchItem::getBatchId, batch.getId())
                        .eq(BpmCommandBatchItem::getStatus, BatchItemStatusEnum.PENDING.getCode())
                        .orderByAsc(BpmCommandBatchItem::getId));
        // 分片预算（生效策略 batchSliceItems，默认 25）：单次消费最多处理切片数项，
        // 剩余项让出调度线程（重新入队、不计失败、占用不变），共享预算内保留
        // 生产和 OA 的推进机会；批量项领取等待仍满足合同（≤30s，切片轮询持续推进）
        int slice = resolveBatchSlice();
        List<BpmCommandBatchItem> toProcess = pending.size() <= slice
                ? pending : pending.subList(0, slice);
        log.info("批量批次开始处理: batchKey={}, batchId={}, pending={}, slice={}, commandId={}",
                batch.getBatchKey(), batch.getId(), pending.size(), Math.min(slice, pending.size()),
                commandId);

        for (BpmCommandBatchItem item : toProcess) {
            // 逐项独立事务：业务拒绝落 REJECTED 并提交计数；基础设施异常向上传播触发命令重试
            Boolean settledNow = txTemplate.execute(status -> {
                boolean settled = settleOneItem(batch, item);
                // 批量按项终态回收占用（方向合同：批量按项终态回收，失败重试不重复加账）；
                // 仅本次实际结算的项回收（重入跳过不重复释放）
                if (settled) {
                    releaseService.onBatchItemTerminal(batchCommand);
                }
                return settled;
            });
            log.debug("批量项结算: itemKey={}, settledNow={}", item.getItemKey(), settledNow);
        }

        if (pending.size() > toProcess.size()) {
            // 未完让出：重新入队由后续轮询继续（下轮仍按切片推进直至收敛）
            boolean requeued = commandQueue.requeueForContinuation(commandId, envelope.getClaimToken());
            if (requeued) {
                throw new CommandContinuationSignal("批量切片让出: batchKey=" + batch.getBatchKey()
                        + ", processed=" + toProcess.size()
                        + ", remaining=" + (pending.size() - toProcess.size()));
            }
            // 重新入队失败=领取权已易主：本次不写终态（当前持有者负责），避免覆盖
            log.warn("批量切片续跑被跳过（领取权已变化）: batchKey={}", batch.getBatchKey());
            return "{\"status\":\"YIELD_SKIPPED\"}";
        }

        // 结算：聚合实际项状态（重复执行安全）+ 批次状态 + 效果权威账本（同事务）
        String resultJson = txTemplate.execute(status -> settleBatch(batch, commandId, envelope));
        return resultJson;
    }

    /** 批量切片预算（生效策略；未启用策略时不切片=旧行为，默认关闭零变化）。 */
    private int resolveBatchSlice() {
        com.sw.ck.bpm.process.entity.BpmResourcePolicy policy =
                admissionService.findActivePolicy();
        if (policy == null || policy.getBatchSliceItems() == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, policy.getBatchSliceItems());
    }

    @Override
    public void onFinalFailure(CommandEnvelope envelope, String reason) {
        // 已结算项的持久结果保留（逐项事务已提交）；批次保持 PROCESSING，
        // 修复后经 requeueFailed 重试只处理剩余 PENDING 项，不做整批回滚
        log.error("批量批次命令最终失败（已处理项结果保留）: commandId={}, reason={}",
                envelope.getCommandId(), reason);
    }

    /** 单项处理与计数（调用方事务内；业务终态写入与批次聚合同事务一致）。
     *  @return true=本次实际结算至终态（重入跳过返回 false，不重复释放占用） */
    private boolean settleOneItem(BpmCommandBatch batch, BpmCommandBatchItem item) {
        BpmCommandBatchItem current = itemMapper.selectById(item.getId());
        if (current == null || !BatchItemStatusEnum.PENDING.getCode().equals(current.getStatus())) {
            return false; // 重入：已终态项不重做
        }
        current.setAttemptCount((current.getAttemptCount() == null ? 0 : current.getAttemptCount()) + 1);
        try {
            FormTxnActionPort.TxnActionResult result = txnActionPort.invoke(
                    new FormTxnActionPort.TxnActionCommand(batch.getActionId(), current.getRecordId(),
                            current.getQuantity(), invocationKey(batch.getBatchKey(), current.getItemKey()),
                            null, null, batch.getActionVersion())).orElseThrow(
                    () -> new IllegalStateException("批量项动作调用无结果"));
            if ("SUCCEEDED".equals(result.status())) {
                current.setStatus(BatchItemStatusEnum.SUCCEEDED.getCode());
                current.setInvocationId(result.invocationId());
                current.setErrorCode(null);
                current.setErrorMsg(null);
            } else {
                current.setStatus(BatchItemStatusEnum.REJECTED.getCode());
                current.setErrorCode(result.errorCode());
                current.setErrorMsg(truncate(result.errorMsg()));
            }
        } catch (BaseException e) {
            // 确定性业务拒绝（含同键异载荷冲突）：落 REJECTED 终态，不触发命令重试
            current.setStatus(BatchItemStatusEnum.REJECTED.getCode());
            current.setErrorCode(e.getCode());
            current.setErrorMsg(truncate(e.getMessage()));
        }
        itemMapper.updateById(current);
        refreshCounters(batch.getId());
        log.info("批量项已结算: batchKey={}, itemKey={}, status={}, attempt={}",
                batch.getBatchKey(), current.getItemKey(), current.getStatus(), current.getAttemptCount());
        return true;
    }

    /** 释放会计载体：冻结资源字段随领取信封携带（toEnvelope 映射命令行），无额外查询。 */
    private com.sw.ck.bpm.process.entity.BpmCommand batchAccountingCommand(CommandEnvelope envelope) {
        com.sw.ck.bpm.process.entity.BpmCommand command = new com.sw.ck.bpm.process.entity.BpmCommand();
        command.setId(envelope.getCommandId());
        command.setTenantId(envelope.getTenantId());
        command.setCommandType(envelope.getCommandType() == null
                ? null : envelope.getCommandType().getCode());
        command.setResourceUnits(envelope.getResourceUnits());
        command.setResourceSegment(envelope.getResourceSegment());
        return command;
    }

    /** 批次结算：全部项终态后聚合状态并写命令效果权威账本（同一事务）。 */
    private String settleBatch(BpmCommandBatch batch, long commandId, CommandEnvelope envelope) {
        refreshCounters(batch.getId());
        BpmCommandBatch current = batchMapper.selectById(batch.getId());
        long pendingCount = itemMapper.selectCount(Wrappers.<BpmCommandBatchItem>lambdaQuery()
                .eq(BpmCommandBatchItem::getBatchId, batch.getId())
                .eq(BpmCommandBatchItem::getStatus, BatchItemStatusEnum.PENDING.getCode()));
        if (pendingCount > 0) {
            // 理论不可达（上方逐项处理后应为 0）；防御：仍存在 PENDING 视为未收敛，交由调度重试
            throw new IllegalStateException("批次仍有未收敛项: batchKey=" + batch.getBatchKey()
                    + ", pending=" + pendingCount);
        }
        String finalStatus = current.getFailedCount() != null && current.getFailedCount() > 0
                ? BatchStatusEnum.PARTIALLY_FAILED.getCode()
                : BatchStatusEnum.COMPLETED.getCode();
        current.setStatus(finalStatus);
        batchMapper.updateById(current);

        String resultJson = "{\"status\":\"" + finalStatus + "\",\"total\":" + current.getTotalCount()
                + ",\"succeeded\":" + current.getSucceededCount()
                + ",\"failed\":" + current.getFailedCount() + "}";
        effectRecorder.record(commandId, envelope.getClaimToken(), envelope.getLogicalCommandId(),
                resultJson, "BATCH:" + batch.getBatchKey());
        log.info("批量批次已结算: batchKey={}, status={}, succeeded={}, failed={}",
                batch.getBatchKey(), finalStatus, current.getSucceededCount(), current.getFailedCount());
        return resultJson;
    }

    /** 按项表实际状态重算批次计数（重复结算安全）。 */
    private void refreshCounters(Long batchId) {
        Long succeeded = itemMapper.selectCount(Wrappers.<BpmCommandBatchItem>lambdaQuery()
                .eq(BpmCommandBatchItem::getBatchId, batchId)
                .eq(BpmCommandBatchItem::getStatus, BatchItemStatusEnum.SUCCEEDED.getCode()));
        Long failed = itemMapper.selectCount(Wrappers.<BpmCommandBatchItem>lambdaQuery()
                .eq(BpmCommandBatchItem::getBatchId, batchId)
                .eq(BpmCommandBatchItem::getStatus, BatchItemStatusEnum.REJECTED.getCode()));
        BpmCommandBatch patch = new BpmCommandBatch();
        patch.setId(batchId);
        patch.setSucceededCount(succeeded.intValue());
        patch.setFailedCount(failed.intValue());
        batchMapper.updateById(patch);
    }

    private void markProcessing(Long batchId) {
        BpmCommandBatch patch = new BpmCommandBatch();
        patch.setId(batchId);
        patch.setStatus(BatchStatusEnum.PROCESSING.getCode());
        batchMapper.updateById(patch);
    }

    private BpmCommandBatch requireBatch(CommandEnvelope envelope) {
        Map<?, ?> payload = readPayload(envelope.getPayload());
        Object batchId = payload.get("batchId");
        if (batchId == null) {
            throw new BaseException(com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR,
                    "BATCH_INVOKE 命令缺少 batchId: commandId=" + envelope.getCommandId());
        }
        BpmCommandBatch batch = batchMapper.selectById(Long.valueOf(String.valueOf(batchId)));
        if (batch == null || !envelope.getTenantId().equals(batch.getTenantId())) {
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.BATCH_NOT_FOUND);
        }
        return batch;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> readPayload(String payload) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(payload == null ? "{}" : payload, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("BATCH_INVOKE payload 解析失败", e);
        }
    }

    static String invocationKey(String batchKey, String itemKey) {
        return "BATCH:" + batchKey + ":" + itemKey;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
