package com.sw.ck.bpm.process.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmChildItem;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTaskFormData;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmChildBatchMapper;
import com.sw.ck.bpm.process.mapper.BpmChildItemMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.facade.FormDataWritebackFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P64 阶段Ⅱ（A05/A06）主子流程编排：派发批次冻结、子完成回写、等待策略结算、
 * 父取消/退回终止与等待节点唤醒（ADR 阶段Ⅱ部分）。
 *
 * <h3>语义锚（主方向 §3.3）</h3>
 * <ul>
 *   <li>派发冻结：批次与批次项在派发同事务登记，预期数/来源行身份与版本/回写配置随批次冻结，
 *       父表后续编辑与排序改变不改变回写对象（稳定行身份）；转办/重试不增加预期数。</li>
 *   <li>等待策略：ALL=全部预期项有效完成且所需回写已提交；ANY=首个有效完成即结算一次；
 *       COUNT=有效完成达 K（K≤本批实际数）即结算一次；NONE=派发意图提交即结算。
 *       失败/拒绝/取消不凑成功数；所需回写未成功不结算。</li>
 *   <li>结算一次：同一批次只推进一次（状态守卫 UPDATE...WHERE status=WAITING）；
 *       结算后迟到完成记 LATE（独立版本留痕，不应用、不覆盖已用快照）。</li>
 *   <li>取消/退回：父终态化时未完成批次 CANCELLED、未完成项 REFUSED，失去写回推进权；
 *       迟到子完成留痕不推进。</li>
 *   <li>回写冲突：目标行版本与派发冻结版本不一致时 CONFLICT 挂起（有权恢复、可诊断、
 *       不覆盖任何现有值）；恢复按当前版本重放并重新结算。</li>
 *   <li>护栏：父子嵌套默认最大 3 层、硬上限 8 层；同一业务根链最多 1000 个自动发起实例；
 *       超限阻止派发并留可诊断原因。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChildOrchestrationService {

    /** 父子嵌套硬上限（根为第 0 层）。 */
    public static final int HARD_MAX_NESTING = 8;
    /** 同一业务根链自动发起实例上限（主方向 §3.5 设计限制）。 */
    public static final int MAX_ROOT_CHAIN_INSTANCES = 1000;

    private static final String POLICY_ALL = "ALL";
    private static final String POLICY_ANY = "ANY";
    private static final String POLICY_COUNT = "COUNT";
    private static final String POLICY_NONE = "NONE";

    private final BpmActionRefMapper actionRefMapper;
    private final BpmChildBatchMapper batchMapper;
    private final BpmChildItemMapper itemMapper;
    private final NodeFormDataService nodeFormDataService;
    private final BpmInstanceService bpmInstanceService;
    private final BpmRuntimeFacade bpmRuntimeFacade;
    private final FormDataWritebackFacade writebackFacade;
    private final ObjectMapper objectMapper;

    /** 父子嵌套上限（默认 3；服务端硬钳 8；UI 可见并经服务端复核）。 */
    @Value("${sw.bpm.orchestration.max-nesting:3}")
    private int configuredMaxNesting;

    public ChildOrchestrationService(BpmActionRefMapper actionRefMapper,
                                     BpmChildBatchMapper batchMapper,
                                     BpmChildItemMapper itemMapper,
                                     NodeFormDataService nodeFormDataService,
                                     BpmInstanceService bpmInstanceService,
                                     BpmRuntimeFacade bpmRuntimeFacade,
                                     FormDataWritebackFacade writebackFacade,
                                     ObjectMapper objectMapper) {
        this.actionRefMapper = actionRefMapper;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.nodeFormDataService = nodeFormDataService;
        this.bpmInstanceService = bpmInstanceService;
        this.bpmRuntimeFacade = bpmRuntimeFacade;
        this.writebackFacade = writebackFacade;
        this.objectMapper = objectMapper;
    }

    // ==================== 派发冻结（派发同事务调用） ====================

    /** 派发侧批次冻结句柄（TriggerExecutionService 项登记与载荷引用）。 */
    public record FrozenBatch(Long batchId, String batchKey, String waitPolicy) {
    }

    /** 按租户读取批次（跨租户拒绝）。 */
    public BpmChildBatch loadBatch(Long tenantId, Long batchId) {
        BpmChildBatch batch = batchMapper.selectById(batchId);
        if (batch == null || !tenantId.equals(batch.getTenantId())) {
            throw new BaseException(BpmErrorCode.CHILD_ACTION_INVALID.getCode(),
                    "子流程批次不存在或不属于当前租户: " + batchId);
        }
        return batch;
    }

    /**
     * 冻结一个子流程派发批次（同一次派发幂等：同 batch_key 复用既有批次）。
     *
     * @throws BaseException CHILD_NESTING_OVER_LIMIT / CHILD_CHAIN_OVER_LIMIT（阻止派发）
     */
    public BpmChildBatch freezeBatch(BpmInstance parent, String triggerId, ActionConfig action,
                                     String execKey, int expectedCount, long round,
                                     String parentFormKey, Long tenantId) {
        int maxNesting = resolveMaxNesting();
        int parentDepth = resolveParentDepth(tenantId, parent.getBusinessKey());
        if (parentDepth >= maxNesting) {
            throw new BaseException(BpmErrorCode.CHILD_NESTING_OVER_LIMIT.getCode(),
                    "父子嵌套深度 " + parentDepth + " 达到上限 " + maxNesting + "，已阻止派发");
        }
        String rootInstanceId = parentDepth == 0
                ? parent.getProcessInstanceId() : resolveRootInstanceId(tenantId, parent.getBusinessKey());
        Long chainCount = batchMapper.selectCount(Wrappers.<BpmChildBatch>lambdaQuery()
                .eq(BpmChildBatch::getTenantId, tenantId)
                .eq(BpmChildBatch::getRootInstanceId, rootInstanceId));
        if (chainCount != null && chainCount >= MAX_ROOT_CHAIN_INSTANCES) {
            throw new BaseException(BpmErrorCode.CHILD_CHAIN_OVER_LIMIT.getCode(),
                    "业务根链 " + rootInstanceId + " 自动发起实例数 " + chainCount
                            + " 达到上限 " + MAX_ROOT_CHAIN_INSTANCES + "，已阻止派发");
        }
        String batchKey = "CHILD:" + execKey + ":" + action.getActionId();
        BpmChildBatch existing = batchMapper.selectOne(Wrappers.<BpmChildBatch>lambdaQuery()
                .eq(BpmChildBatch::getTenantId, tenantId)
                .eq(BpmChildBatch::getBatchKey, batchKey)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        BpmChildBatch batch = new BpmChildBatch();
        batch.setBatchKey(batchKey);
        batch.setParentInstanceId(parent.getProcessInstanceId());
        batch.setParentDefKey(parent.getProcessDefKey());
        batch.setRootInstanceId(rootInstanceId);
        batch.setParentDepth(parentDepth);
        batch.setTriggerId(triggerId);
        batch.setActionId(action.getActionId());
        batch.setRoundNo(round);
        String policy = normalizePolicy(action.getWaitPolicy());
        batch.setWaitPolicy(policy);
        batch.setWaitCount(POLICY_COUNT.equals(policy)
                ? requireWaitCount(action, expectedCount) : null);
        batch.setExpectedCount(expectedCount);
        batch.setSourceRecordId(parent.getBusinessKey());
        batch.setSourceRecordVersion(parentFormKey == null || parent.getBusinessKey() == null ? null
                : writebackFacade.readVersion(tenantId, parentFormKey, parent.getBusinessKey(),
                        null, null).orElse(null));
        // NONE：可靠派发意图提交即结算（结算动作在登记完成后统一落定，这里先按 WAITING
        // 登记，由 settleBatch 的 NONE 分支在项登记后立即结算，保持单一结算通道）
        batch.setStatus(BpmChildBatch.STATUS_WAITING);
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException e) {
            // 同键并发派发：幂等复用既有批次
            return batchMapper.selectOne(Wrappers.<BpmChildBatch>lambdaQuery()
                    .eq(BpmChildBatch::getTenantId, tenantId)
                    .eq(BpmChildBatch::getBatchKey, batchKey)
                    .last("limit 1"));
        }
        batch.setConfigJson(safeJson(action));
        batchMapper.updateById(batch);
        return batch;
    }

    /**
     * 登记批次项（派发项冻结；同键幂等吸收）。行级派发冻结来源行当前版本（回写冲突检测基准）。
     */
    public void registerItem(BpmChildBatch batch, Long actionRefId, String itemKey,
                             String sourceRowId, String summary, String targetDefKey,
                             String targetFormKey, String parentFormKey, Long tenantId) {
        Long sourceRowVersion = null;
        if (sourceRowId != null && parentFormKey != null) {
            ActionConfig action = parseConfig(batch.getConfigJson());
            String parentTableField = action != null && action.getWriteBack() != null
                    ? action.getWriteBack().getParentTableField() : null;
            sourceRowVersion = writebackFacade.readVersion(tenantId, parentFormKey,
                    batch.getSourceRecordId(), parentTableField, sourceRowId).orElse(null);
        }
        BpmChildItem item = new BpmChildItem();
        item.setBatchId(batch.getId());
        item.setItemKey(truncate(itemKey, 120));
        item.setActionRefId(actionRefId);
        item.setSourceRowId(sourceRowId == null ? null : truncate(sourceRowId, 60));
        item.setSourceRowVersion(sourceRowVersion);
        item.setSourceSummary(summary);
        item.setTargetDefKey(targetDefKey);
        item.setTargetFormKey(targetFormKey);
        item.setStatus(BpmChildItem.STATUS_DISPATCHED);
        try {
            itemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            // 并发重复登记：唯一键吸收，不重复登记
            log.info("子流程批次项幂等冲突（并发重复登记）: batchId={}, itemKey={}",
                    batch.getId(), itemKey);
        }
    }

    /**
     * NONE 策略批次：全部项登记完成后立即结算（派发意图已提交即可继续）。
     */
    public void settleIfNone(BpmChildBatch batch) {
        if (POLICY_NONE.equals(batch.getWaitPolicy())
                && BpmChildBatch.STATUS_WAITING.equals(batch.getStatus())) {
            settleBatch(batch.getId());
        }
    }

    // ==================== 子实例终态（回写 + 结算） ====================

    /**
     * 子流程实例终态回调：本实例作为子流程时回写并推进父批次结算；
     * 非子流程实例零行为。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onChildProcessTerminal(BpmInstance child, String terminalStatus) {
        if (child == null || child.getBusinessKey() == null || child.getTenantId() == null) {
            return;
        }
        List<BpmChildItem> items = itemMapper.selectList(Wrappers.<BpmChildItem>lambdaQuery()
                .eq(BpmChildItem::getTenantId, child.getTenantId())
                .eq(BpmChildItem::getTargetRecordId, child.getBusinessKey())
                .eq(BpmChildItem::getStatus, BpmChildItem.STATUS_DISPATCHED));
        if (items.isEmpty()) {
            // 批次项的 target_record_id 由消费链异步回填，完成事件可能先到达：
            // 按动作意图（target_record_id=子实例 businessKey）反查批次项并回填关联
            List<BpmActionRef> refs = actionRefMapper.selectList(Wrappers.<BpmActionRef>lambdaQuery()
                    .eq(BpmActionRef::getTenantId, child.getTenantId())
                    .eq(BpmActionRef::getTargetRecordId, child.getBusinessKey()));
            if (refs.isEmpty()) {
                return;
            }
            List<Long> refIds = refs.stream().map(BpmActionRef::getId).toList();
            items = itemMapper.selectList(Wrappers.<BpmChildItem>lambdaQuery()
                    .eq(BpmChildItem::getTenantId, child.getTenantId())
                    .in(BpmChildItem::getActionRefId, refIds)
                    .eq(BpmChildItem::getStatus, BpmChildItem.STATUS_DISPATCHED));
            for (BpmChildItem item : items) {
                item.setTargetRecordId(child.getBusinessKey());
                item.setTargetInstanceId(child.getProcessInstanceId());
            }
        }
        if (items.isEmpty()) {
            return;
        }
        for (BpmChildItem item : items) {
            BpmChildBatch batch = batchMapper.selectById(item.getBatchId());
            if (batch == null) {
                continue;
            }
            if (!"APPROVED".equals(terminalStatus)) {
                // 失败/拒绝/取消不凑成功数：FAILED 计阻断项（ALL 阻断、ANY/COUNT 忽略）
                item.setStatus(BpmChildItem.STATUS_FAILED);
                item.setErrorText("子流程终态 " + terminalStatus + "，不计有效完成");
            } else if (BpmChildBatch.STATUS_WAITING.equals(batch.getStatus())) {
                applyWriteback(item, batch, child);
            } else {
                // 批次已结算/取消：迟到完成只留版本化记录，不应用、不推进
                item.setStatus(BpmChildBatch.STATUS_CANCELLED.equals(batch.getStatus())
                        ? BpmChildItem.STATUS_REFUSED : BpmChildItem.STATUS_LATE);
                item.setErrorText(BpmChildItem.STATUS_REFUSED.equals(item.getStatus())
                        ? "父流程已取消/退回，本结果失去写回推进权（留痕）"
                        : "批次已结算，本结果为迟到反馈（版本化留痕，不覆盖已用快照）");
                item.setWritebackJson(resultSnapshot(item, batch, child));
                item.setWritebackTime(LocalDateTime.now());
                item.setWritebackSource(writebackSource(child, batch));
            }
            itemMapper.updateById(item);
            if (BpmChildBatch.STATUS_WAITING.equals(batch.getStatus())) {
                settleBatch(batch.getId());
            }
        }
    }

    /** 有效完成 + 所需回写提交（A06：稳定行身份 + 版本守卫，允许字段受控）。 */
    private void applyWriteback(BpmChildItem item, BpmChildBatch batch, BpmInstance child) {
        ActionConfig action = parseConfig(batch.getConfigJson());
        ActionConfig.WriteBackConfig wb = action == null ? null : action.getWriteBack();
        if (wb == null) {
            // 未配置回写：子流程有效完成即成功
            item.setStatus(BpmChildItem.STATUS_WRITTEN);
            return;
        }
        BpmTaskFormData result = latestSubmitted(child.getTenantId(), child.getProcessInstanceId(),
                wb.getResultNodeKey());
        if (result == null) {
            item.setStatus(BpmChildItem.STATUS_FAILED);
            item.setErrorText("必需回写缺失：结果节点 " + wb.getResultNodeKey() + " 无有效最终提交");
            return;
        }
        Map<String, Object> data = nodeFormDataService.parseData(result.getDataText());
        Map<String, Object> recorded = new LinkedHashMap<>();

        // 行级回写（稳定行身份 + 派发版本守卫）
        if (wb.getTableField() != null && !wb.getTableField().isBlank()) {
            Map<String, Object> rowFields = resolveRowFields(wb, data, item);
            if (rowFields == null) {
                item.setStatus(BpmChildItem.STATUS_FAILED);
                item.setErrorText("必需回写缺失：子结果未包含来源行 " + item.getSourceRowId());
                return;
            }
            if (!rowFields.isEmpty()) {
                BpmInstance parent = bpmInstanceService
                        .findByProcessInstanceId(batch.getParentInstanceId()).orElse(null);
                if (parent == null || parent.getFormKey() == null) {
                    item.setStatus(BpmChildItem.STATUS_FAILED);
                    item.setErrorText("父实例不可用，回写被拒绝");
                    return;
                }
                FormDataWritebackFacade.WritebackResult outcome = writebackFacade.applyWriteback(
                        new FormDataWritebackFacade.WritebackRequest(child.getTenantId(),
                                parent.getFormKey(), batch.getSourceRecordId(),
                                wb.getParentTableField(), item.getSourceRowId(),
                                item.getSourceRowVersion(), rowFields, child.getInitiatorId())).orElse(null);
                if (outcome == null) {
                    item.setStatus(BpmChildItem.STATUS_FAILED);
                    item.setErrorText("回写目标上下文缺失（表单/记录不可用）");
                    return;
                }
                if (FormDataWritebackFacade.WritebackResult.NOT_FOUND.equals(outcome.status())) {
                    item.setStatus(BpmChildItem.STATUS_FAILED);
                    item.setErrorText("回写目标来源行不可用或越权，回写被拒绝: "
                            + item.getSourceRowId());
                    return;
                }
                if (FormDataWritebackFacade.WritebackResult.VERSION_CONFLICT.equals(outcome.status())) {
                    // 冲突挂起：可诊断、有权恢复，不覆盖任何现有值
                    item.setStatus(BpmChildItem.STATUS_CONFLICT);
                    item.setErrorText("回写与来源行当前版本冲突（当前权威版本 "
                            + outcome.rowVersion() + "），已挂起等待有权处置");
                    return;
                }
                recorded.put("row", rowFields);
            }
        }

        // 主记录字段回写
        Map<String, Object> mainFields = new LinkedHashMap<>();
        if (wb.getMainFields() != null) {
            for (ActionConfig.FieldMapping mapping : wb.getMainFields()) {
                mainFields.put(mapping.getToField(), data.get(mapping.getFromField()));
            }
        }
        if (!mainFields.isEmpty()) {
            BpmInstance parent = bpmInstanceService
                    .findByProcessInstanceId(batch.getParentInstanceId()).orElse(null);
            if (parent == null || parent.getFormKey() == null) {
                item.setStatus(BpmChildItem.STATUS_FAILED);
                item.setErrorText("父实例不可用，回写被拒绝");
                return;
            }
            FormDataWritebackFacade.WritebackResult outcome = writebackFacade.applyWriteback(
                    new FormDataWritebackFacade.WritebackRequest(child.getTenantId(),
                            parent.getFormKey(), batch.getSourceRecordId(), null, null,
                            batch.getSourceRecordVersion(), mainFields, child.getInitiatorId())).orElse(null);
            if (outcome == null || FormDataWritebackFacade.WritebackResult.NOT_FOUND
                    .equals(outcome.status())) {
                item.setStatus(BpmChildItem.STATUS_FAILED);
                item.setErrorText("回写目标主记录不可用，回写被拒绝");
                return;
            }
            if (FormDataWritebackFacade.WritebackResult.VERSION_CONFLICT.equals(outcome.status())) {
                item.setStatus(BpmChildItem.STATUS_CONFLICT);
                item.setErrorText("回写与父记录当前版本冲突（当前权威版本 "
                        + outcome.rowVersion() + "），已挂起等待有权处置");
                return;
            }
            recorded.put("main", mainFields);
        }
        item.setStatus(BpmChildItem.STATUS_WRITTEN);
        item.setWritebackJson(safeJson(recorded));
        item.setWritebackTime(LocalDateTime.now());
        item.setWritebackSource(writebackSource(child, batch));
    }

    /** 子结果表格中匹配稳定来源行的允许列值；来源行缺失返回 null（必需回写不满足）。 */
    private Map<String, Object> resolveRowFields(ActionConfig.WriteBackConfig wb,
                                                 Map<String, Object> data, BpmChildItem item) {
        Object tableValue = data.get(wb.getTableField());
        if (!(tableValue instanceof List<?> rows)) {
            return wb.getFields() == null || wb.getFields().isEmpty() ? Map.of() : null;
        }
        Map<String, Object> matched = null;
        for (Object rowObject : rows) {
            if (rowObject instanceof Map<?, ?> row
                    && item.getSourceRowId() != null
                    && item.getSourceRowId().equals(String.valueOf(row.get(wb.getRowKeyField())))) {
                matched = new LinkedHashMap<>((Map<String, Object>) row);
                break;
            }
        }
        if (matched == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (wb.getFields() != null) {
            for (ActionConfig.FieldMapping mapping : wb.getFields()) {
                fields.put(mapping.getToField(), matched.get(mapping.getFromField()));
            }
        }
        return fields;
    }

    /** 版本化结果快照（迟到/取消留痕；不应用）。 */
    @SuppressWarnings("unchecked")
    private String resultSnapshot(BpmChildItem item, BpmChildBatch batch, BpmInstance child) {
        try {
            ActionConfig action = parseConfig(batch.getConfigJson());
            ActionConfig.WriteBackConfig wb = action == null ? null : action.getWriteBack();
            if (wb == null) {
                return "{}";
            }
            BpmTaskFormData result = latestSubmitted(child.getTenantId(),
                    child.getProcessInstanceId(), wb.getResultNodeKey());
            if (result == null) {
                return "{}";
            }
            Map<String, Object> data = nodeFormDataService.parseData(result.getDataText());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            if (wb.getMainFields() != null) {
                Map<String, Object> main = new LinkedHashMap<>();
                wb.getMainFields().forEach(mapping ->
                        main.put(mapping.getToField(), data.get(mapping.getFromField())));
                snapshot.put("main", main);
            }
            if (wb.getTableField() != null && data.get(wb.getTableField()) instanceof List<?> rows) {
                for (Object rowObject : rows) {
                    if (rowObject instanceof Map<?, ?> row
                            && item.getSourceRowId() != null
                            && item.getSourceRowId().equals(String.valueOf(row.get(wb.getRowKeyField())))) {
                        snapshot.put("row", row);
                        break;
                    }
                }
            }
            return safeJson(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String writebackSource(BpmInstance child, BpmChildBatch batch) {
        return "child=" + child.getProcessInstanceId() + ",batch=" + batch.getBatchKey();
    }

    private BpmTaskFormData latestSubmitted(Long tenantId, String processInstanceId, String nodeKey) {
        List<BpmTaskFormData> rows = nodeFormDataService.listSubmittedByNode(
                tenantId, processInstanceId, nodeKey);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    // ==================== 等待策略结算 ====================

    /** 测试桥接：包内可见的直接结算入口（语义与内部结算通道一致）。 */
    void settleForTest(Long batchId) {
        settleBatch(batchId);
    }


    /**
     * 批次结算（幂等：状态守卫单次推进；ALL 阻断落 BLOCKED 可诊断）。
     */
    private void settleBatch(Long batchId) {
        BpmChildBatch batch = batchMapper.selectById(batchId);
        if (batch == null || !BpmChildBatch.STATUS_WAITING.equals(batch.getStatus())) {
            return;
        }
        List<BpmChildItem> items = itemMapper.selectList(Wrappers.<BpmChildItem>lambdaQuery()
                .eq(BpmChildItem::getTenantId, batch.getTenantId())
                .eq(BpmChildItem::getBatchId, batchId));
        int successes = 0;
        int open = 0;
        String firstBlockReason = null;
        for (BpmChildItem item : items) {
            switch (item.getStatus()) {
                case BpmChildItem.STATUS_WRITTEN -> successes++;
                case BpmChildItem.STATUS_DISPATCHED -> open++;
                case BpmChildItem.STATUS_CONFLICT, BpmChildItem.STATUS_FAILED,
                     BpmChildItem.STATUS_REFUSED -> {
                    if (firstBlockReason == null) {
                        firstBlockReason = item.getStatus() + ": "
                                + (item.getErrorText() == null ? "未知原因" : item.getErrorText());
                    }
                }
                default -> {
                    // LATE 不参与本批结算口径
                }
            }
        }
        String policy = batch.getWaitPolicy();
        boolean settled = switch (policy == null ? POLICY_ALL : policy) {
            case POLICY_ANY -> successes >= 1;
            case POLICY_COUNT -> successes >= Math.max(1,
                    batch.getWaitCount() == null ? 1 : batch.getWaitCount());
            case POLICY_NONE -> true;
            default -> open == 0 && firstBlockReason == null
                    && successes >= batch.getExpectedCount();
        };
        if (settled) {
            int updated = batchMapper.update(null, Wrappers.<BpmChildBatch>lambdaUpdate()
                    .eq(BpmChildBatch::getId, batchId)
                    .eq(BpmChildBatch::getStatus, BpmChildBatch.STATUS_WAITING)
                    .set(BpmChildBatch::getStatus, BpmChildBatch.STATUS_SETTLED)
                    .set(BpmChildBatch::getSettledCount, successes)
                    .set(BpmChildBatch::getSettledAt, LocalDateTime.now()));
            if (updated > 0) {
                log.info("子流程批次已结算: batchKey={}, policy={}, successes={}/{}",
                        batch.getBatchKey(), policy, successes, batch.getExpectedCount());
                signalParentIfReady(batch);
            }
            return;
        }
        if (POLICY_ALL.equals(policy) && open == 0 && firstBlockReason != null) {
            batchMapper.update(null, Wrappers.<BpmChildBatch>lambdaUpdate()
                    .eq(BpmChildBatch::getId, batchId)
                    .eq(BpmChildBatch::getStatus, BpmChildBatch.STATUS_WAITING)
                    .set(BpmChildBatch::getStatus, BpmChildBatch.STATUS_BLOCKED)
                    .set(BpmChildBatch::getBlockReason, truncate(firstBlockReason, 500)));
            log.info("子流程批次阻断: batchKey={}, reason={}", batch.getBatchKey(), firstBlockReason);
        }
    }

    // ==================== 等待节点唤醒 ====================

    /** 等待节点到达（由 SubflowWaitPortImpl 单实现适配器调用；本类不再直接实现端口以避免注入歧义）。 */
    public void onWaitNodeArrival(Long tenantId, String processInstanceId, String activityId) {
        // 令牌到达：引用批次已全部终态则立即唤醒，否则挂起等待结算侧信号
        signalIfNoOpenBatches(tenantId, processInstanceId, activityId);
    }

    /** 批次结算后按父图等待节点引用检查唤醒（只推进一次由节点唤醒幂等保证）。 */
    private void signalParentIfReady(BpmChildBatch batch) {
        try {
            BpmInstance parent = bpmInstanceService
                    .findByProcessInstanceId(batch.getParentInstanceId()).orElse(null);
            if (parent == null) {
                return;
            }
            ProcessGraph graph = nodeFormDataService.loadGraph(parent.getProcessDefKey(),
                    parent.getDefVersion());
            if (graph == null || graph.getElements() == null) {
                return;
            }
            for (var element : graph.getElements()) {
                if (!"node".equals(element.getKind())
                        || !"SUBFLOW_WAIT".equalsIgnoreCase(element.getType())) {
                    continue;
                }
                if (!referencesAction(element, batch.getActionId(), graph)) {
                    continue;
                }
                signalIfNoOpenBatches(parent.getTenantId(), parent.getProcessInstanceId(),
                        element.getId());
            }
        } catch (Exception e) {
            // 唤醒失败不回滚结算事实；等待节点保持挂起，可经查询与恢复路径处置
            log.error("等待节点唤醒检查失败: parent={}, batch={}",
                    batch.getParentInstanceId(), batch.getBatchKey(), e);
        }
    }

    /** 引用批次全部终态时唤醒指定等待节点（幂等；无等待执行流由门面吸收）。 */
    private void signalIfNoOpenBatches(Long tenantId, String parentInstanceId, String activityId) {
        BpmInstance parent = bpmInstanceService.findByProcessInstanceId(parentInstanceId)
                .orElse(null);
        if (parent == null) {
            return;
        }
        ProcessGraph graph = nodeFormDataService.loadGraph(parent.getProcessDefKey(),
                parent.getDefVersion());
        Set<String> references = waitReferences(graph, activityId);
        if (references.isEmpty()) {
            return;
        }
        boolean anyWaiting = references.stream().anyMatch(actionId ->
                batchMapper.selectCount(Wrappers.<BpmChildBatch>lambdaQuery()
                        .eq(BpmChildBatch::getTenantId, tenantId)
                        .eq(BpmChildBatch::getParentInstanceId, parentInstanceId)
                        .eq(BpmChildBatch::getActionId, actionId)
                        .eq(BpmChildBatch::getStatus, BpmChildBatch.STATUS_WAITING)) > 0);
        if (anyWaiting) {
            log.info("等待节点保持挂起（存在 WAITING 批次）: parent={}, activityId={}",
                    parentInstanceId, activityId);
            return;
        }
        bpmRuntimeFacade.signalWaitNode(parentInstanceId, activityId).ifPresentOrElse(
                outcome -> log.info("等待节点唤醒结果: parent={}, activityId={}, outcome={}",
                        parentInstanceId, activityId, outcome),
                () -> log.info("等待节点唤醒目标不在运行期: parent={}, activityId={}",
                        parentInstanceId, activityId));
    }

    /** 等待节点引用的动作集合（配置为空 = 本图全部 CHILD 动作）。 */
    private Set<String> waitReferences(ProcessGraph graph, String waitNodeId) {
        Set<String> childActions = new LinkedHashSet<>();
        if (graph.getTriggers() != null) {
            graph.getTriggers().forEach(trigger -> {
                if (trigger.getBranches() == null) {
                    return;
                }
                trigger.getBranches().forEach(branch -> {
                    if (branch.getActions() == null) {
                        return;
                    }
                    branch.getActions().stream()
                            .filter(ActionConfig::isChild)
                            .map(ActionConfig::getActionId)
                            .forEach(childActions::add);
                });
            });
        }
        if (graph.getElements() == null) {
            return Set.of();
        }
        return graph.getElements().stream()
                .filter(element -> "node".equals(element.getKind())
                        && waitNodeId.equals(element.getId()))
                .findFirst()
                .map(element -> {
                    if (element.getConfig() != null
                            && element.getConfig().get("waitActionIds") instanceof List<?> refs
                            && !refs.isEmpty()) {
                        Set<String> configured = new LinkedHashSet<>();
                        refs.forEach(ref -> configured.add(String.valueOf(ref)));
                        return configured;
                    }
                    return childActions;
                })
                .orElse(Set.of());
    }

    private boolean referencesAction(com.sw.ck.bpm.api.dto.GraphElement element, String actionId,
                                     ProcessGraph graph) {
        Set<String> references = waitReferences(graph, element.getId());
        return references.contains(actionId);
    }

    // ==================== 父取消/退回终止 ====================

    /**
     * 父流程终态化（取消/退回/驳回/撤回/废弃/合法完成）：未完成批次失去写回推进权。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void cancelBatchesForParent(BpmInstance parent, String reason) {
        if (parent == null || parent.getTenantId() == null) {
            return;
        }
        List<BpmChildBatch> batches = batchMapper.selectList(Wrappers.<BpmChildBatch>lambdaQuery()
                .eq(BpmChildBatch::getTenantId, parent.getTenantId())
                .eq(BpmChildBatch::getParentInstanceId, parent.getProcessInstanceId())
                .eq(BpmChildBatch::getStatus, BpmChildBatch.STATUS_WAITING));
        for (BpmChildBatch batch : batches) {
            int updated = batchMapper.update(null, Wrappers.<BpmChildBatch>lambdaUpdate()
                    .eq(BpmChildBatch::getId, batch.getId())
                    .eq(BpmChildBatch::getStatus, BpmChildBatch.STATUS_WAITING)
                    .set(BpmChildBatch::getStatus, BpmChildBatch.STATUS_CANCELLED)
                    .set(BpmChildBatch::getBlockReason, truncate(
                            "父流程 " + reason + "，本批未完成子流程失去写回推进权", 500)));
            if (updated > 0) {
                itemMapper.update(null, Wrappers.<BpmChildItem>lambdaUpdate()
                        .eq(BpmChildItem::getTenantId, parent.getTenantId())
                        .eq(BpmChildItem::getBatchId, batch.getId())
                        .eq(BpmChildItem::getStatus, BpmChildItem.STATUS_DISPATCHED)
                        .set(BpmChildItem::getStatus, BpmChildItem.STATUS_REFUSED)
                        .set(BpmChildItem::getErrorText,
                                "父流程 " + reason + "，失去向当前轮次写回/推进的资格（留痕）"));
                log.info("子流程批次已取消: batchKey={}, reason={}", batch.getBatchKey(), reason);
            }
        }
    }

    // ==================== 冲突恢复（有权用户） ====================

    /**
     * 回写冲突恢复：按当前权威版本重放允许字段回写（不重复已生效结果：仅 CONFLICT 可恢复）。
     */
    public record WritebackRetryOutcome(String status, String message) {
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public WritebackRetryOutcome retryWriteback(Long tenantId, Long itemId, Long actorId) {
        BpmChildItem item = itemMapper.selectById(itemId);
        if (item == null || !tenantId.equals(item.getTenantId())) {
            throw new BaseException(BpmErrorCode.CHILD_WRITEBACK_CONFLICT.getCode(),
                    "回写记录不存在或不属于当前租户");
        }
        if (!BpmChildItem.STATUS_CONFLICT.equals(item.getStatus())) {
            return new WritebackRetryOutcome("SKIP", "仅回写冲突项可恢复，当前状态 " + item.getStatus());
        }
        BpmChildBatch batch = batchMapper.selectById(item.getBatchId());
        if (batch == null) {
            return new WritebackRetryOutcome("SKIP", "批次不存在");
        }
        if (BpmChildBatch.STATUS_CANCELLED.equals(batch.getStatus())) {
            return new WritebackRetryOutcome("SKIP", "批次已取消，无写回推进权");
        }
        BpmInstance child = bpmInstanceService.findByBusinessKey(item.getTargetRecordId())
                .orElse(null);
        if (child == null) {
            return new WritebackRetryOutcome("SKIP", "子实例不可用");
        }
        // 恢复重放：清空冻结版本守卫（按当前权威版本应用，人工决策后生效）
        item.setSourceRowVersion(null);
        applyWriteback(item, batch, child);
        itemMapper.updateById(item);
        if (BpmChildBatch.STATUS_WAITING.equals(batch.getStatus())) {
            settleBatch(batch.getId());
        }
        return new WritebackRetryOutcome(item.getStatus(),
                BpmChildItem.STATUS_WRITTEN.equals(item.getStatus())
                        ? "回写已按当前版本应用" : "回写仍被挂起: " + item.getErrorText());
    }

    // ==================== 回查视图（A11） ====================

    public record ChildItemView(Long id, String itemKey, String status, String sourceRowId,
                                Long sourceRowVersion, String targetRecordId, String targetInstanceId,
                                String writebackJson, String writebackSource, String errorText) {
    }

    public record ChildBatchView(Long id, String batchKey, String parentInstanceId, String triggerId,
                                 String actionId, Long roundNo, String waitPolicy, Integer waitCount,
                                 Integer expectedCount, Integer settledCount, String status,
                                 String blockReason, LocalDateTime settledAt, Integer parentDepth,
                                 List<ChildItemView> items) {
    }

    public List<ChildBatchView> listBatches(Long tenantId, String parentInstanceId) {
        List<BpmChildBatch> batches = batchMapper.selectList(Wrappers.<BpmChildBatch>lambdaQuery()
                .eq(BpmChildBatch::getTenantId, tenantId)
                .eq(BpmChildBatch::getParentInstanceId, parentInstanceId)
                .orderByAsc(BpmChildBatch::getId));
        List<ChildBatchView> views = new ArrayList<>();
        for (BpmChildBatch batch : batches) {
            List<BpmChildItem> items = itemMapper.selectList(Wrappers.<BpmChildItem>lambdaQuery()
                    .eq(BpmChildItem::getTenantId, tenantId)
                    .eq(BpmChildItem::getBatchId, batch.getId())
                    .orderByAsc(BpmChildItem::getId));
            views.add(new ChildBatchView(batch.getId(), batch.getBatchKey(),
                    batch.getParentInstanceId(), batch.getTriggerId(), batch.getActionId(),
                    batch.getRoundNo(), batch.getWaitPolicy(), batch.getWaitCount(),
                    batch.getExpectedCount(), batch.getSettledCount(), batch.getStatus(),
                    batch.getBlockReason(), batch.getSettledAt(), batch.getParentDepth(),
                    items.stream().map(item -> new ChildItemView(item.getId(), item.getItemKey(),
                            item.getStatus(), item.getSourceRowId(), item.getSourceRowVersion(),
                            item.getTargetRecordId(), item.getTargetInstanceId(),
                            item.getWritebackJson(), item.getWritebackSource(), item.getErrorText()))
                            .toList()));
        }
        return views;
    }

    // ==================== 工具 ====================

    /** 父实例自身深度：被更高层批次派发过 = 派发批次深度 + 1；根为 0。 */
    private int resolveParentDepth(Long tenantId, String parentBusinessKey) {
        if (parentBusinessKey == null) {
            return 0;
        }
        List<BpmChildItem> parentItems = itemMapper.selectList(Wrappers.<BpmChildItem>lambdaQuery()
                .eq(BpmChildItem::getTenantId, tenantId)
                .eq(BpmChildItem::getTargetRecordId, parentBusinessKey));
        int maxDepth = -1;
        for (BpmChildItem item : parentItems) {
            BpmChildBatch batch = batchMapper.selectById(item.getBatchId());
            if (batch != null && batch.getParentDepth() != null) {
                maxDepth = Math.max(maxDepth, batch.getParentDepth());
            }
        }
        return maxDepth + 1;
    }

    private String resolveRootInstanceId(Long tenantId, String parentBusinessKey) {
        List<BpmChildItem> parentItems = itemMapper.selectList(Wrappers.<BpmChildItem>lambdaQuery()
                .eq(BpmChildItem::getTenantId, tenantId)
                .eq(BpmChildItem::getTargetRecordId, parentBusinessKey));
        for (BpmChildItem item : parentItems) {
            BpmChildBatch batch = batchMapper.selectById(item.getBatchId());
            if (batch != null && batch.getRootInstanceId() != null) {
                return batch.getRootInstanceId();
            }
        }
        return null;
    }

    private int resolveMaxNesting() {
        int value = configuredMaxNesting <= 0 ? 3 : configuredMaxNesting;
        return Math.min(value, HARD_MAX_NESTING);
    }

    private String normalizePolicy(String policy) {
        String value = policy == null ? POLICY_ALL : policy.toUpperCase();
        return switch (value) {
            case POLICY_ANY, POLICY_COUNT, POLICY_NONE -> value;
            default -> POLICY_ALL;
        };
    }

    private Integer requireWaitCount(ActionConfig action, int expectedCount) {
        Integer count = action.getWaitCount();
        if (count == null || count < 1) {
            throw new BaseException(BpmErrorCode.CHILD_ACTION_INVALID.getCode(),
                    "动作 " + action.getActionId() + ": COUNT 等待策略必须配置正整数 K");
        }
        if (count > expectedCount) {
            throw new BaseException(BpmErrorCode.CHILD_ACTION_INVALID.getCode(),
                    "动作 " + action.getActionId() + ": COUNT K=" + count
                            + " 超过本批实际子流程数 " + expectedCount + "（整体拒绝不截断）");
        }
        return count;
    }

    private ActionConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(configJson, ActionConfig.class);
        } catch (Exception e) {
            log.warn("批次冻结配置解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
