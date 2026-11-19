package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.vo.ActionRefVO;
import com.sw.ck.bpm.process.vo.ChildBatchVO;
import com.sw.ck.bpm.process.vo.RetryResultVO;
import com.sw.ck.bpm.process.vo.TriggerExecVO;
import com.sw.ck.bpm.process.vo.TriggerPreviewVO;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTriggerExec;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmVariableSnapshotService;
import com.sw.ck.bpm.process.service.ActionRefRecoveryService;
import com.sw.ck.bpm.process.service.BpmScriptFunctionContextFactory;
import com.sw.ck.bpm.process.service.ChildOrchestrationService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import com.sw.ck.bpm.process.service.TriggerExecutionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.sw.ck.bpm.process.entity.ActionRefStatusEnum;
import com.sw.ck.common.value.DynamicData;

/**
 * P64 触发器与动作回查控制器（A03/A04/A11）。
 * <p>
 * 预览按真实实例上下文只读评估（不落任何行、不登记命令）；实例维度触发执行与
 * 动作意图按链回查（触发→意图→目标实例/记录）；失败意图可由有权用户重试
 * （requeueFailed 复用同键命令）。
 * </p>
 */
@Log4j2
@RestController
@RequestMapping("/workflow")
public class BpmTriggerController {

    /** BF20261019-03：判断脚本执行超时毫秒（可配置；缺省沿用主方向 §3.5 的 5000ms）。 */
    @Value("${sw.bpm.script.timeout-ms:5000}")
    private long scriptTimeoutMs = BpmScriptEvaluatePort.DEFAULT_TIMEOUT_MS;

    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private BpmInstanceService bpmInstanceService;
    @Autowired
    private NodeFormDataService nodeFormDataService;
    @Autowired
    private BpmVariableSnapshotService variableSnapshotService;
    @Autowired
    private BpmScriptEvaluatePort scriptRunner;
    @Autowired
    private BpmTriggerExecMapper triggerExecMapper;
    @Autowired
    private BpmActionRefMapper actionRefMapper;
    @Autowired
    private ActionRefRecoveryService actionRefRecoveryService;
    @Autowired
    private ChildOrchestrationService childOrchestrationService;
    /** BF20261013-021 脚本函数上下文工厂（可为 null：预览退化为空上下文）。 */
    @Autowired
    private BpmScriptFunctionContextFactory scriptFunctionContextFactory;

    // ==================== 预览（只读，无副作用） ====================

    /**
     * 受控预览：按真实实例上下文解析快照并评估脚本+匹配，不落任何行、不登记命令。
     */
    @PostMapping("/triggers/preview")
    public R<TriggerPreviewVO> preview(@RequestBody Map<String, Object> request) {
        String instanceId = str(request.get("instanceId"));
        String triggerId = str(request.get("triggerId"));
        if (instanceId == null || triggerId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "instanceId 与 triggerId 必填");
        }
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        if (tenantId == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(instanceId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在"));
        ProcessGraph graph = nodeFormDataService.loadGraph(instance.getProcessDefKey(), instance.getDefVersion());
        TriggerConfig trigger = graph == null || graph.getTriggers() == null ? null
                : graph.getTriggers().stream()
                        .filter(item -> triggerId.equals(item.getTriggerId()))
                        .findFirst().orElse(null);
        if (trigger == null) {
            throw new BaseException(BpmErrorCode.TRIGGER_INVALID.getCode(),
                    "触发器在实例冻结版本中不存在: " + triggerId);
        }
        TriggerPreviewVO result = new TriggerPreviewVO();
        BpmVariableSnapshotService.SnapshotResult snapshot = variableSnapshotService.buildSnapshot(
                tenantId, instance, graph.getVariables(), trigger.getVariables(),
                trigger.getNodeKey(), nodeFormDataService.currentRound(instanceId));
        result.setVariables(DynamicData.of(snapshot.values()));
        result.setTooLarge(snapshot.tooLarge());
        result.setMissingRequired(snapshot.missingRequired());
        result.setErrors(snapshot.errors());
        if (snapshot.failed()) {
            result.setKind("SNAPSHOT_FAILED");
            return R.ok(result);
        }
        ScriptFunctionContext functionContext =
                scriptFunctionContextFactory == null
                        ? ScriptFunctionContext.EMPTY
                        : scriptFunctionContextFactory.build(trigger.getScript(), instanceId, tenantId);
        BpmScriptEvaluatePort.ScriptOutcome run = scriptRunner
                .run(trigger.getScript(), snapshot.values(), scriptTimeoutMs,
                        functionContext)
                .orElseThrow(() -> new IllegalStateException(
                        "BpmScriptEvaluatePort#run 契约恒 present，empty 属契约违约"));
        result.setKind(run.kind());
        result.setValue(DynamicData.of(run.value()));
        result.setResultType(run.typeName());
        result.setErrorMessage(run.errorMessage());
        result.setDurationMs(run.durationMs());
        // BF20261013-021：预览展示脚本会写入的变量（只读展示，不落库）
        result.setVariableWrites(DynamicData.of(
                run.variableWrites() == null ? Map.of() : run.variableWrites()));
        if (run.ok() && run.value() != null) {
            TriggerConfig.TriggerBranch matched = matchPreview(trigger, run.typeName(), run.value());
            result.setMatchedBranchId(matched == null ? null : matched.getBranchId());
            result.setMatchedActions(matched == null || matched.getActions() == null ? List.of()
                    : matched.getActions().stream()
                            .map(action -> new TriggerPreviewVO.MatchedActionVO(
                                    nullSafe(action.getActionId()),
                                    nullSafe(action.getType()),
                                    nullSafe(action.getTargetProcessDefKey())))
                            .toList());
        }
        return R.ok(result);
    }

    // ==================== 实例维度回查 ====================

    /**
     * 解析实例标识：兼容流程实例 ID（Flowable）与 sw_bpm_instance 主键（“我发起的”列表行 ID）。
     */
    private String resolveProcessInstanceId(Long tenantId, String instanceId) {
        var direct = bpmInstanceService.findByProcessInstanceId(instanceId);
        if (direct.isPresent()) {
            return instanceId;
        }
        try {
            Long primaryKey = Long.parseLong(instanceId);
            var byPk = bpmInstanceService.getById(primaryKey);
            return byPk == null ? instanceId : byPk.getProcessInstanceId();
        } catch (NumberFormatException e) {
            return instanceId;
        }
    }

    @GetMapping("/instances/{instanceId}/trigger-execs")
    public R<List<TriggerExecVO>> listTriggerExecs(@PathVariable String instanceId) {
        Long tenantId = requireTenantId();
        String processInstanceId = resolveProcessInstanceId(tenantId, instanceId);
        List<BpmTriggerExec> rows = triggerExecMapper.selectList(
                Wrappers.<BpmTriggerExec>lambdaQuery()
                        .eq(BpmTriggerExec::getTenantId, tenantId)
                        .eq(BpmTriggerExec::getProcessInstanceId, processInstanceId)
                        .orderByDesc(BpmTriggerExec::getId));
        return R.ok(rows.stream().map(this::toExecView).toList());
    }

    @GetMapping("/instances/{instanceId}/action-refs")
    public R<List<ActionRefVO>> listActionRefs(@PathVariable String instanceId) {
        Long tenantId = requireTenantId();
        String processInstanceId = resolveProcessInstanceId(tenantId, instanceId);
        List<BpmActionRef> rows = actionRefMapper.selectList(
                Wrappers.<BpmActionRef>lambdaQuery()
                        .eq(BpmActionRef::getTenantId, tenantId)
                        .eq(BpmActionRef::getProcessInstanceId, processInstanceId)
                        .orderByAsc(BpmActionRef::getId));
        return R.ok(rows.stream().map(this::toRefView).toList());
    }

    /**
     * 动作意图受控恢复（复审05 P1-06b）：按 ORCH/FLOW_START 持久状态给出可诊断结果或
     * 受控恢复动作（不再 500 死路）。已成功启动（目标实例存在）一律拒绝。
     * 权限：实例查看权（workflow:instance:view）；租户边界由登录态强制。
     */
    @PreAuthorize("@ss.hasPermi('workflow:instance:view')")
    @PostMapping("/action-refs/{refId}/retry")
    public R<RetryResultVO> retryActionRef(@PathVariable Long refId) {
        Long tenantId = requireTenantId();
        BpmActionRef ref = actionRefMapper.selectById(refId);
        if (ref == null || !tenantId.equals(ref.getTenantId())) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "动作意图不存在");
        }
        // 重试门槛按持久事实：目标实例已存在=已成功启动，拒绝恢复
        if (ActionRefStatusEnum.STARTED.getCode().equals(ref.getStatus()) || resolveTargetInstanceId(ref) != null) {
            throw new BaseException(BpmErrorCode.ACTION_INVALID.getCode(),
                    "动作意图已成功启动，无需重试");
        }
        ActionRefRecoveryService.RetryOutcome outcome = actionRefRecoveryService.retry(ref);
        RetryResultVO result = new RetryResultVO();
        result.setCommandId(outcome.commandId());
        result.setStatus(outcome.status());
        result.setMessage(outcome.message());
        return R.ok(result);
    }

    // ==================== 子流程批次回查与回写恢复（P64 阶段Ⅱ A05/A06/A11） ====================

    /**
     * 实例的子流程派发批次/批次项/回写与等待结果回查（父子单据、来源行、等待原因可追溯）。
     */
    @GetMapping("/instances/{instanceId}/child-batches")
    public R<List<ChildBatchVO>> listChildBatches(@PathVariable String instanceId) {
        Long tenantId = requireTenantId();
        if (childOrchestrationService == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "子流程编排能力未装配");
        }
        String processInstanceId = resolveProcessInstanceId(tenantId, instanceId);
        return R.ok(childOrchestrationService.listBatches(tenantId, processInstanceId).stream()
                .map(batch -> {
                    ChildBatchVO item = new ChildBatchVO();
                    item.setId(batch.id());
                    item.setBatchKey(batch.batchKey());
                    item.setParentInstanceId(batch.parentInstanceId());
                    item.setTriggerId(batch.triggerId());
                    item.setActionId(batch.actionId());
                    item.setRoundNo(batch.roundNo());
                    item.setWaitPolicy(batch.waitPolicy());
                    item.setWaitCount(batch.waitCount());
                    item.setExpectedCount(batch.expectedCount());
                    item.setSettledCount(batch.settledCount());
                    item.setStatus(batch.status());
                    item.setBlockReason(batch.blockReason());
                    item.setSettledAt(batch.settledAt());
                    item.setParentDepth(batch.parentDepth());
                    item.setItems(batch.items());
                    return item;
                })
                .toList());
    }

    /**
     * 回写冲突受控恢复：仅 CONFLICT 项可由有权用户按当前权威版本重放（不重复已生效结果）。
     * 权限：实例查看权（workflow:instance:view）；租户边界由登录态强制。
     */
    @PreAuthorize("@ss.hasPermi('workflow:instance:view')")
    @PostMapping("/child-items/{itemId}/retry-writeback")
    public R<RetryResultVO> retryChildWriteback(@PathVariable Long itemId) {
        Long tenantId = requireTenantId();
        if (childOrchestrationService == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "子流程编排能力未装配");
        }
        Long actorId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId();
        ChildOrchestrationService.WritebackRetryOutcome outcome =
                childOrchestrationService.retryWriteback(tenantId, itemId, actorId);
        RetryResultVO result = new RetryResultVO();
        result.setStatus(outcome.status());
        result.setMessage(outcome.message());
        return R.ok(result);
    }

    // ==================== 工具 ====================

    private TriggerConfig.TriggerBranch matchPreview(TriggerConfig trigger, String typeName, Object value) {
        if (trigger.getBranches() == null) {
            return null;
        }
        for (TriggerConfig.TriggerBranch branch : trigger.getBranches()) {
            String branchType = branch.getMatchType() == null ? "" : branch.getMatchType().toUpperCase();
            if (!branchType.equals(typeName)) {
                continue;
            }
            try {
                boolean matched = switch (branchType) {
                    case "NUMBER" -> new BigDecimal(String.valueOf(value))
                            .compareTo(new BigDecimal(branch.getMatchValue())) == 0;
                    case "BOOLEAN" -> Boolean.parseBoolean(branch.getMatchValue()) == (Boolean) value;
                    case "STRING" -> branch.getMatchValue().equals(String.valueOf(value));
                    default -> false;
                };
                if (matched) {
                    return branch;
                }
            } catch (Exception ignore) {
                // 分支匹配值非法在发布校验拦截；预览按不命中处理
            }
        }
        return null;
    }

    private TriggerExecVO toExecView(BpmTriggerExec row) {
        TriggerExecVO item = new TriggerExecVO();
        item.setId(row.getId());
        item.setProcessInstanceId(row.getProcessInstanceId());
        item.setTriggerId(row.getTriggerId());
        item.setEventType(row.getEventType());
        item.setNodeKey(row.getNodeKey());
        item.setRoundNo(row.getRoundNo());
        item.setExecKey(row.getExecKey());
        item.setStatus(row.getStatus());
        item.setResultType(row.getResultType());
        item.setResultValue(row.getResultValue());
        item.setMatchedBranchId(row.getMatchedBranchId());
        item.setDisposition(row.getDisposition());
        item.setErrorText(row.getErrorText());
        item.setSnapshot(nodeFormDataService == null ? null : row.getSnapshotText());
        item.setDurationMs(row.getDurationMs());
        item.setCreateTime(row.getCreateTime());
        return item;
    }

    private ActionRefVO toRefView(BpmActionRef row) {
        ActionRefVO item = new ActionRefVO();
        item.setId(row.getId());
        item.setExecId(row.getExecId());
        item.setProcessInstanceId(row.getProcessInstanceId());
        item.setTriggerId(row.getTriggerId());
        item.setActionId(row.getActionId());
        item.setActionType(row.getActionType());
        item.setItemKey(row.getItemKey());
        item.setItemSummary(row.getItemSummary());
        item.setCommandKey(row.getCommandKey());
        item.setCommandId(row.getCommandId());
        item.setTargetDefKey(row.getTargetDefKey());
        item.setTargetFormKey(row.getTargetFormKey());
        item.setTargetRecordId(row.getTargetRecordId());
        item.setTargetInstanceId(resolveTargetInstanceId(row));
        item.setStatus(resolveDisplayStatus(row));
        item.setErrorText(row.getErrorText());
        item.setCreateTime(row.getCreateTime());
        return item;
    }

    /**
     * 展示状态解析（审查02 P1-06b）：STARTING=目标记录已建且 FLOW_START 已受理（启动中），
     * 目标实例由既有消费链异步创建；回查按持久事实解析——实例已存在才展示 STARTED，
     * 不以受理冒称目标已启动。STARTING 展示为启动中，FLOW_START 失败窗口保持可诊断。
     */
    private String resolveDisplayStatus(BpmActionRef row) {
        if (!"STARTING".equals(row.getStatus())) {
            return row.getStatus();
        }
        return resolveTargetInstanceId(row) == null ? "STARTING" : "STARTED";
    }

    /**
     * 关联实例解析：目标实例由 FLOW_START 命令异步二段创建，ref 行的 target_instance_id
     * 不在意图受理事务内回填；回查按 target_record_id（= 目标 business_key）动态解析。
     */
    private String resolveTargetInstanceId(BpmActionRef row) {
        if (row.getTargetInstanceId() != null && !row.getTargetInstanceId().isBlank()) {
            return row.getTargetInstanceId();
        }
        if (row.getTargetRecordId() == null || row.getTargetRecordId().isBlank()) {
            return null;
        }
        return bpmInstanceService.findByBusinessKey(row.getTargetRecordId())
                .map(BpmInstance::getProcessInstanceId)
                .orElse(null);
    }

    private Long requireTenantId() {
        Long tenantId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getTenantId();
        if (tenantId == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        return tenantId;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
