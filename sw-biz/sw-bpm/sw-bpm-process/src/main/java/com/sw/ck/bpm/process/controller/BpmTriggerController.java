package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTriggerExec;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmVariableSnapshotService;
import com.sw.ck.bpm.process.service.ActionRefRecoveryService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import com.sw.ck.bpm.process.service.TriggerExecutionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P64 触发器与动作回查控制器（A03/A04/A11）。
 * <p>
 * 预览按真实实例上下文只读评估（不落任何行、不登记命令）；实例维度触发执行与
 * 动作意图按链回查（触发→意图→目标实例/记录）；失败意图可由有权用户重试
 * （requeueFailed 复用同键命令）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/workflow")
public class BpmTriggerController {

    private final BpmTaskFacade bpmTaskFacade;
    private final BpmInstanceService bpmInstanceService;
    private final NodeFormDataService nodeFormDataService;
    private final BpmVariableSnapshotService variableSnapshotService;
    private final BpmScriptEvaluatePort scriptRunner;
    private final BpmTriggerExecMapper triggerExecMapper;
    private final BpmActionRefMapper actionRefMapper;
    private final ActionRefRecoveryService actionRefRecoveryService;

    public BpmTriggerController(BpmTaskFacade bpmTaskFacade,
                                BpmInstanceService bpmInstanceService,
                                NodeFormDataService nodeFormDataService,
                                BpmVariableSnapshotService variableSnapshotService,
                                BpmScriptEvaluatePort scriptRunner,
                                BpmTriggerExecMapper triggerExecMapper,
                                BpmActionRefMapper actionRefMapper,
                                ActionRefRecoveryService actionRefRecoveryService) {
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmInstanceService = bpmInstanceService;
        this.nodeFormDataService = nodeFormDataService;
        this.variableSnapshotService = variableSnapshotService;
        this.scriptRunner = scriptRunner;
        this.triggerExecMapper = triggerExecMapper;
        this.actionRefMapper = actionRefMapper;
        this.actionRefRecoveryService = actionRefRecoveryService;
    }

    // ==================== 预览（只读，无副作用） ====================

    /**
     * 受控预览：按真实实例上下文解析快照并评估脚本+匹配，不落任何行、不登记命令。
     */
    @PostMapping("/triggers/preview")
    public R<Map<String, Object>> preview(@RequestBody Map<String, Object> request) {
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
        Map<String, Object> result = new LinkedHashMap<>();
        BpmVariableSnapshotService.SnapshotResult snapshot = variableSnapshotService.buildSnapshot(
                tenantId, instance, graph.getVariables(), trigger.getVariables(),
                trigger.getNodeKey(), nodeFormDataService.currentRound(instanceId));
        result.put("variables", snapshot.values());
        result.put("tooLarge", snapshot.tooLarge());
        result.put("missingRequired", snapshot.missingRequired());
        result.put("errors", snapshot.errors());
        if (snapshot.failed()) {
            result.put("kind", "SNAPSHOT_FAILED");
            return R.ok(result);
        }
        BpmScriptEvaluatePort.ScriptOutcome run = scriptRunner
                .run(trigger.getScript(), snapshot.values(), BpmScriptEvaluatePort.DEFAULT_TIMEOUT_MS)
                .orElseThrow(() -> new IllegalStateException(
                        "BpmScriptEvaluatePort#run 契约恒 present，empty 属契约违约"));
        result.put("kind", run.kind());
        result.put("value", run.value());
        result.put("resultType", run.typeName());
        result.put("errorMessage", run.errorMessage());
        result.put("durationMs", run.durationMs());
        if (run.ok() && run.value() != null) {
            TriggerConfig.TriggerBranch matched = matchPreview(trigger, run.typeName(), run.value());
            result.put("matchedBranchId", matched == null ? null : matched.getBranchId());
            result.put("matchedActions", matched == null || matched.getActions() == null ? List.of()
                    : matched.getActions().stream()
                            .map(action -> Map.of("actionId", nullSafe(action.getActionId()),
                                    "type", nullSafe(action.getType()),
                                    "targetDefKey", nullSafe(action.getTargetProcessDefKey())))
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
    public R<List<Map<String, Object>>> listTriggerExecs(@PathVariable String instanceId) {
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
    public R<List<Map<String, Object>>> listActionRefs(@PathVariable String instanceId) {
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
    public R<Map<String, Object>> retryActionRef(@PathVariable Long refId) {
        Long tenantId = requireTenantId();
        BpmActionRef ref = actionRefMapper.selectById(refId);
        if (ref == null || !tenantId.equals(ref.getTenantId())) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "动作意图不存在");
        }
        // 重试门槛按持久事实：目标实例已存在=已成功启动，拒绝恢复
        if ("STARTED".equals(ref.getStatus()) || resolveTargetInstanceId(ref) != null) {
            throw new BaseException(BpmErrorCode.ACTION_INVALID.getCode(),
                    "动作意图已成功启动，无需重试");
        }
        ActionRefRecoveryService.RetryOutcome outcome = actionRefRecoveryService.retry(ref);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandId", outcome.commandId());
        result.put("status", outcome.status());
        result.put("message", outcome.message());
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
                    case "NUMBER" -> new java.math.BigDecimal(String.valueOf(value))
                            .compareTo(new java.math.BigDecimal(branch.getMatchValue())) == 0;
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

    private Map<String, Object> toExecView(BpmTriggerExec row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("processInstanceId", row.getProcessInstanceId());
        item.put("triggerId", row.getTriggerId());
        item.put("eventType", row.getEventType());
        item.put("nodeKey", row.getNodeKey());
        item.put("roundNo", row.getRoundNo());
        item.put("execKey", row.getExecKey());
        item.put("status", row.getStatus());
        item.put("resultType", row.getResultType());
        item.put("resultValue", row.getResultValue());
        item.put("matchedBranchId", row.getMatchedBranchId());
        item.put("disposition", row.getDisposition());
        item.put("errorText", row.getErrorText());
        item.put("snapshot", nodeFormDataService == null ? null
                : row.getSnapshotText());
        item.put("durationMs", row.getDurationMs());
        item.put("createTime", row.getCreateTime());
        return item;
    }

    private Map<String, Object> toRefView(BpmActionRef row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("execId", row.getExecId());
        item.put("processInstanceId", row.getProcessInstanceId());
        item.put("triggerId", row.getTriggerId());
        item.put("actionId", row.getActionId());
        item.put("actionType", row.getActionType());
        item.put("itemKey", row.getItemKey());
        item.put("itemSummary", row.getItemSummary());
        item.put("commandKey", row.getCommandKey());
        item.put("commandId", row.getCommandId());
        item.put("targetDefKey", row.getTargetDefKey());
        item.put("targetFormKey", row.getTargetFormKey());
        item.put("targetRecordId", row.getTargetRecordId());
        item.put("targetInstanceId", resolveTargetInstanceId(row));
        item.put("status", resolveDisplayStatus(row));
        item.put("errorText", row.getErrorText());
        item.put("createTime", row.getCreateTime());
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
