package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.entity.BpmCommandBatch;
import com.sw.ck.bpm.process.entity.BpmCommandBatchItem;
import com.sw.ck.bpm.process.entity.BatchItemStatusEnum;
import com.sw.ck.bpm.process.entity.BatchStatusEnum;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.port.FormTxnActionPort;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台批量受控动作调用服务实现（P62 分级执行 S3）。
 * <p>
 * 受理口径：与 {@code FormTxnActionPort} 同一权限（{@code form:action:invoke}）与
 * 租户边界；批次键同租户唯一（重放返回原批次）；项数 1—500；项键批次内唯一。
 * 受理冻结动作发布版本；执行由 {@code BATCH_INVOKE} 命令异步逐项处理。
 * </p>
 */
@Service
public class TxnBatchServiceImpl implements TxnBatchService {

    private static final Logger log = LoggerFactory.getLogger(TxnBatchServiceImpl.class);

    /** 方向合同：每批 1—500 项。 */
    public static final int MIN_ITEMS = 1;
    public static final int MAX_ITEMS = 500;

    /** 与 FormTxnActionPortImpl 相同的方法权限码：批量受理沿用同一授权口径。 */
    static final String INVOKE_PERMISSION = "form:action:invoke";

    /** 批次管理查询权限：非发起人回查他人批次所需（超管旁路）。 */
    static final String MANAGE_PERMISSION = "form:action:manage";

    /**
     * 新能力部署门禁（P62 G5a/U06 兼容合同）：批量受理新入口默认关闭。
     * 关闭时即便持有 {@code form:action:invoke} 授权也不能产生新类型命令，
     * 与旧版本消费者并存期间保证零新类型落库；须旧消费者退出且在途核清后
     * 由部署配置协调开启。消费侧 {@code BatchInvokeCommandHandler} 注册
     * 受同一开关约束。
     */
    @Value("${sw.bpm.txn-batch.enabled:false}")
    private boolean txnBatchEnabled;

    private final BpmCommandBatchMapper batchMapper;
    private final BpmCommandBatchItemMapper itemMapper;
    private final BpmCommandQueue commandQueue;
    private final FormTxnActionPort txnActionPort;

    public TxnBatchServiceImpl(BpmCommandBatchMapper batchMapper,
                               BpmCommandBatchItemMapper itemMapper,
                               BpmCommandQueue commandQueue,
                               FormTxnActionPort txnActionPort) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.commandQueue = commandQueue;
        this.txnActionPort = txnActionPort;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TxnBatchView submit(TxnBatchSubmitRequest request) {
        // 能力开关先于授权判定：默认关=部署门禁，持权限用户在旧消费者存活期也不产生新类型
        if (!txnBatchEnabled) {
            log.warn("批量受理被能力门禁拒绝（sw.bpm.txn-batch.enabled=false）: batchKey={}",
                    request == null ? null : request.getBatchKey());
            throw new BaseException(BpmErrorCode.BATCH_CAPABILITY_DISABLED.getCode(),
                    BpmErrorCode.BATCH_CAPABILITY_DISABLED.getMessage());
        }
        LoginUser operator = requireAuthorizedOperator();
        validateRequest(request);

        // 批次重放：同租户批次键已存在 → 返回原批次（不重建、不重复执行）
        BpmCommandBatch existing = batchMapper.selectOne(Wrappers.<BpmCommandBatch>lambdaQuery()
                .eq(BpmCommandBatch::getTenantId, operator.getTenantId())
                .eq(BpmCommandBatch::getBatchKey, request.getBatchKey())
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("批次重放命中原批次: batchKey={}, batchId={}", existing.getBatchKey(), existing.getId());
            return toView(existing, findItems(existing.getId()), true);
        }

        // 绑定动作发布期校验（同租户、已发布；跨租户按不存在，不泄露存在性）
        FormTxnActionPort.TxnActionDescriptor descriptor = txnActionPort.describe(request.getActionId())
                .orElseThrow(() -> new BaseException(BpmErrorCode.BATCH_NOT_FOUND.getCode(),
                        "绑定动作不存在、跨租户不可达或未发布: " + request.getActionId()));
        if (!"PUBLISHED".equals(descriptor.status())) {
            throw new BaseException(BpmErrorCode.BATCH_NOT_FOUND.getCode(),
                    "绑定动作未发布（当前状态 " + descriptor.status() + "）");
        }

        BpmCommandBatch batch = new BpmCommandBatch();
        batch.setBatchKey(request.getBatchKey());
        batch.setActionId(request.getActionId());
        batch.setActionVersion(descriptor.currentVersion());
        batch.setStatus(BatchStatusEnum.PENDING.getCode());
        batch.setTotalCount(request.getItems().size());
        batch.setSucceededCount(0);
        batch.setFailedCount(0);
        batch.setInitiatorId(operator.getUserId());
        batchMapper.insert(batch);

        List<BpmCommandBatchItem> items = new ArrayList<>(request.getItems().size());
        for (TxnBatchSubmitRequest.Item item : request.getItems()) {
            BpmCommandBatchItem entity = new BpmCommandBatchItem();
            entity.setBatchId(batch.getId());
            entity.setItemKey(item.getItemKey());
            entity.setRecordId(item.getRecordId());
            entity.setQuantity(item.getQuantity());
            entity.setStatus(BatchItemStatusEnum.PENDING.getCode());
            entity.setAttemptCount(0);
            itemMapper.insert(entity);
            items.add(entity);
        }

        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.BATCH_INVOKE);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey("BATCH:" + request.getBatchKey());
        envelope.setLogicalCommandId("BATCH:" + request.getBatchKey());
        envelope.setTenantId(operator.getTenantId());
        envelope.setInitiatorId(operator.getUserId());
        envelope.setTier("BULK");
        envelope.setCompletionPoint("BATCH_SETTLED");
        envelope.setPayload("{\"batchId\":" + batch.getId()
                + ",\"batchKey\":\"" + escape(request.getBatchKey()) + "\""
                + ",\"actionId\":\"" + escape(request.getActionId()) + "\"}");
        Long commandId = commandQueue.enqueue(envelope);

        batch.setCommandId(commandId);
        batchMapper.updateById(batch);
        log.info("批量批次已受理: batchKey={}, batchId={}, commandId={}, items={}",
                batch.getBatchKey(), batch.getId(), commandId, items.size());
        return toView(batch, items, false);
    }

    @Override
    public TxnBatchView get(String batchKey) {
        // 回查权限口径：调用权限或管理权限其一（管理者回查批次不属于业务调用）
        LoginUser operator = requireAuthorizedOperator(s -> s.contains(INVOKE_PERMISSION)
                || s.contains(MANAGE_PERMISSION));
        BpmCommandBatch batch = batchMapper.selectOne(Wrappers.<BpmCommandBatch>lambdaQuery()
                .eq(BpmCommandBatch::getTenantId, operator.getTenantId())
                .eq(BpmCommandBatch::getBatchKey, batchKey)
                .last("LIMIT 1"));
        if (batch == null) {
            throw new BaseException(BpmErrorCode.BATCH_NOT_FOUND);
        }
        // U07 查询边界：批次项携带业务结果，发起人可见自己的批次；
        // 非发起人须持管理权限（form:action:manage）或超管，同租户其他受理人不默认可见
        boolean isInitiator = operator.getUserId() != null
                && operator.getUserId().equals(batch.getInitiatorId());
        boolean isManager = operator.isSuperAdmin()
                || (operator.getPermissions() != null
                        && operator.getPermissions().contains(MANAGE_PERMISSION));
        if (!isInitiator && !isManager) {
            throw new BaseException(BpmErrorCode.BATCH_NOT_FOUND);
        }
        return toView(batch, findItems(batch.getId()), false);
    }

    // ==================== 内部 ====================

    private void validateRequest(TxnBatchSubmitRequest request) {
        if (request == null || isBlank(request.getBatchKey()) || isBlank(request.getActionId())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "batchKey 与 actionId 不能为空");
        }
        if (request.getItems() == null || request.getItems().size() < MIN_ITEMS
                || request.getItems().size() > MAX_ITEMS) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "批量项数必须在 " + MIN_ITEMS + "—"
                            + MAX_ITEMS + " 之间（实际 " + (request.getItems() == null ? 0 : request.getItems().size()) + "）");
        }
        Set<String> keys = new HashSet<>();
        for (TxnBatchSubmitRequest.Item item : request.getItems()) {
            if (item == null || isBlank(item.getItemKey())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "批次项缺少稳定项键（itemKey）");
            }
            if (!keys.add(item.getItemKey())) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "批次内项键重复: " + item.getItemKey());
            }
        }
    }

    private List<BpmCommandBatchItem> findItems(Long batchId) {
        return itemMapper.selectList(Wrappers.<BpmCommandBatchItem>lambdaQuery()
                .eq(BpmCommandBatchItem::getBatchId, batchId)
                .orderByAsc(BpmCommandBatchItem::getId));
    }

    private TxnBatchView toView(BpmCommandBatch batch, List<BpmCommandBatchItem> items, boolean replay) {
        TxnBatchView view = new TxnBatchView();
        view.setBatchKey(batch.getBatchKey());
        view.setActionId(batch.getActionId());
        view.setActionVersion(batch.getActionVersion());
        view.setStatus(batch.getStatus());
        view.setTotalCount(batch.getTotalCount());
        view.setSucceededCount(batch.getSucceededCount());
        view.setFailedCount(batch.getFailedCount());
        view.setCommandId(batch.getCommandId());
        view.setReplay(replay);
        List<TxnBatchView.Item> itemViews = new ArrayList<>(items.size());
        for (BpmCommandBatchItem item : items) {
            TxnBatchView.Item itemView = new TxnBatchView.Item();
            itemView.setItemKey(item.getItemKey());
            itemView.setRecordId(item.getRecordId());
            itemView.setQuantity(item.getQuantity());
            itemView.setStatus(item.getStatus());
            itemView.setInvocationId(item.getInvocationId());
            itemView.setErrorCode(item.getErrorCode());
            itemView.setErrorMsg(item.getErrorMsg());
            itemView.setAttemptCount(item.getAttemptCount());
            itemViews.add(itemView);
        }
        view.setItems(itemViews);
        return view;
    }

    private LoginUser requireAuthorizedOperator() {
        return requireAuthorizedOperator(permissions -> permissions.contains(INVOKE_PERMISSION));
    }

    private LoginUser requireAuthorizedOperator(java.util.function.Predicate<java.util.Set<String>> permissionCheck) {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        java.util.Set<String> permissions = user.getPermissions() == null
                ? java.util.Set.of() : new java.util.HashSet<>(user.getPermissions());
        boolean allowed = user.isSuperAdmin() || permissionCheck.test(permissions);
        if (!allowed) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(),
                    "无权受理批量调用：缺少 " + INVOKE_PERMISSION);
        }
        return user;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
