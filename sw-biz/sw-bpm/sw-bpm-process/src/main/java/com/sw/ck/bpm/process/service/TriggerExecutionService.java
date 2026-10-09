package com.sw.ck.bpm.process.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTriggerExec;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P64 Trigger 受控判断与配置化动作执行（ADR-P64-001 §3/§4，A03+A04）。
 * <p>
 * 在完成任务的同事务内评估（快照同事务一致读点）；脚本只判断，异常/超时/超限/未匹配
 * 落可诊断 exec 行、不产生动作、不回滚业务办理；命中分支的动作为每项同事务登记
 * ORCH_ACTION_START 可靠意图（sw_bpm_command + sw_bpm_action_ref），消费侧建目标实例。
 * </p>
 */
@Slf4j
@Service
public class TriggerExecutionService {

    public static final String EVENT_TASK_SUBMITTED = "TASK_SUBMITTED";
    public static final String EVENT_NODE_ROUND_COMPLETED = "NODE_ROUND_COMPLETED";
    public static final String EVENT_PROCESS_COMPLETED = "PROCESS_COMPLETED";

    private static final int DEFAULT_MAX_DISPATCH = 50;
    private static final int HARD_MAX_DISPATCH = 200;

    private final NodeFormDataService nodeFormDataService;
    private final BpmVariableSnapshotService variableSnapshotService;
    private final BpmScriptEvaluatePort scriptRunner;
    private final BpmCommandQueue commandQueue;
    private final BpmTriggerExecMapper triggerExecMapper;
    private final BpmActionRefMapper actionRefMapper;
    private final BpmTaskFacade bpmTaskFacade;
    private final ObjectMapper objectMapper;

    public TriggerExecutionService(NodeFormDataService nodeFormDataService,
                                   BpmVariableSnapshotService variableSnapshotService,
                                   BpmScriptEvaluatePort scriptRunner,
                                   BpmCommandQueue commandQueue,
                                   BpmTriggerExecMapper triggerExecMapper,
                                   BpmActionRefMapper actionRefMapper,
                                   BpmTaskFacade bpmTaskFacade,
                                   ObjectMapper objectMapper) {
        this.nodeFormDataService = nodeFormDataService;
        this.variableSnapshotService = variableSnapshotService;
        this.scriptRunner = scriptRunner;
        this.commandQueue = commandQueue;
        this.triggerExecMapper = triggerExecMapper;
        this.actionRefMapper = actionRefMapper;
        this.bpmTaskFacade = bpmTaskFacade;
        this.objectMapper = objectMapper;
    }

    /** 派发项（集合项冻结身份）。 */
    private record ActionItem(String itemKey, Map<String, Object> summary, Map<String, Object> itemValues) {
    }

    // ==================== 事件入口（加入调用方事务） ====================

    /**
     * 任务合法完成（APPROVE/DISAPPROVE）后触发：TASK_SUBMITTED + NODE_ROUND_COMPLETED 判定。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onTaskActionCompleted(BpmInstance instance, BpmTaskDTO task, ApprovalAction action) {
        if (action != ApprovalAction.APPROVE && action != ApprovalAction.DISAPPROVE) {
            // 退回/驳回等生命周期动作各自处置，不冒充合法完成（方向 §3.2）
            return;
        }
        ProcessGraph graph = nodeFormDataService.loadGraph(instance.getProcessDefKey(), instance.getDefVersion());
        if (graph == null || graph.getTriggers() == null || graph.getTriggers().isEmpty()) {
            return;
        }
        long round = nodeFormDataService.currentRound(instance.getProcessInstanceId());
        String nodeKey = task.getTaskDefinitionKey();
        graph.getTriggers().stream()
                .filter(trigger -> EVENT_TASK_SUBMITTED.equalsIgnoreCase(trigger.getEvent()))
                .filter(trigger -> nodeKey != null && nodeKey.equals(trigger.getNodeKey()))
                .forEach(trigger -> evaluate(instance, graph, trigger, EVENT_TASK_SUBMITTED,
                        nodeKey, round, task.getTaskId()));
        boolean nodeQuiet = bpmTaskFacade.queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of()).stream()
                .noneMatch(active -> nodeKey != null && nodeKey.equals(active.getTaskDefinitionKey()));
        if (nodeQuiet) {
            graph.getTriggers().stream()
                    .filter(trigger -> EVENT_NODE_ROUND_COMPLETED.equalsIgnoreCase(trigger.getEvent()))
                    .filter(trigger -> nodeKey != null && nodeKey.equals(trigger.getNodeKey()))
                    .forEach(trigger -> evaluate(instance, graph, trigger, EVENT_NODE_ROUND_COMPLETED,
                            nodeKey, round, null));
        }
    }

    /**
     * 流程合法完成（APPROVED 终态）后触发 PROCESS_COMPLETED；驳回/废弃/撤回不触发。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onProcessCompleted(BpmInstance instance, String terminalStatus) {
        if (!"APPROVED".equals(terminalStatus)) {
            return;
        }
        ProcessGraph graph = nodeFormDataService.loadGraph(instance.getProcessDefKey(), instance.getDefVersion());
        if (graph == null || graph.getTriggers() == null || graph.getTriggers().isEmpty()) {
            return;
        }
        long round = nodeFormDataService.currentRound(instance.getProcessInstanceId());
        graph.getTriggers().stream()
                .filter(trigger -> EVENT_PROCESS_COMPLETED.equalsIgnoreCase(trigger.getEvent()))
                .forEach(trigger -> evaluate(instance, graph, trigger, EVENT_PROCESS_COMPLETED,
                        null, round, null));
    }

    // ==================== 评估 ====================

    private void evaluate(BpmInstance instance, ProcessGraph graph, TriggerConfig trigger,
                          String event, String nodeKey, long round, String scopeTaskId) {
        String execKey = buildExecKey(instance, trigger, event, round, scopeTaskId);
        Long tenantId = instance.getTenantId();
        Long existing = triggerExecMapper.selectCount(Wrappers.<BpmTriggerExec>lambdaQuery()
                .eq(BpmTriggerExec::getTenantId, tenantId)
                .eq(BpmTriggerExec::getExecKey, execKey));
        if (existing != null && existing > 0) {
            return;
        }
        long begin = System.currentTimeMillis();
        BpmTriggerExec exec = new BpmTriggerExec();
        exec.setProcessInstanceId(instance.getProcessInstanceId());
        exec.setProcessDefKey(instance.getProcessDefKey());
        exec.setDefVersion(instance.getDefVersion());
        exec.setTriggerId(trigger.getTriggerId());
        exec.setEventType(event);
        exec.setNodeKey(nodeKey);
        exec.setRoundNo(round);
        exec.setExecKey(execKey);
        try {
            if (trigger.getScript() == null || trigger.getScript().isBlank()) {
                exec.setStatus("FAILED");
                exec.setErrorText(BpmErrorCode.TRIGGER_SCRIPT_FAILED.getMessage() + ": 脚本为空");
                insertExec(exec);
                return;
            }
            BpmVariableSnapshotService.SnapshotResult snapshot = variableSnapshotService.buildSnapshot(
                    tenantId, instance, graph.getVariables(), trigger.getVariables(), nodeKey, round);
            if (snapshot.tooLarge()) {
                exec.setStatus("FAILED");
                exec.setErrorText(BpmErrorCode.VARIABLE_SNAPSHOT_TOO_LARGE.getMessage());
                exec.setDurationMs(elapsed(begin));
                insertExec(exec);
                return;
            }
            if (!snapshot.errors().isEmpty()) {
                exec.setStatus("FAILED");
                exec.setErrorText("变量解析失败: " + String.join("; ", snapshot.errors()));
                exec.setDurationMs(elapsed(begin));
                insertExec(exec);
                return;
            }
            if (!snapshot.missingRequired().isEmpty()) {
                exec.setStatus("FAILED");
                exec.setDisposition("BLOCK");
                exec.setErrorText("必填变量缺失，触发被阻止: " + String.join(", ", snapshot.missingRequired()));
                exec.setDurationMs(elapsed(begin));
                insertExec(exec);
                return;
            }
            exec.setSnapshotText(snapshot.json());
            BpmScriptEvaluatePort.ScriptOutcome result = scriptRunner
                    .run(trigger.getScript(), snapshot.values(), BpmScriptEvaluatePort.DEFAULT_TIMEOUT_MS)
                    .orElseThrow(() -> new IllegalStateException(
                            "BpmScriptEvaluatePort#run 契约恒 present，empty 属契约违约"));
            exec.setDurationMs(result.durationMs());
            switch (result.kind()) {
                case BpmScriptEvaluatePort.ScriptOutcome.KIND_TIMEOUT,
                     BpmScriptEvaluatePort.ScriptOutcome.KIND_RESOURCE_LIMIT -> {
                    exec.setStatus("FAILED");
                    exec.setErrorText(BpmErrorCode.TRIGGER_RESOURCE_LIMIT.getMessage() + ": "
                            + result.errorMessage());
                    insertExec(exec);
                    return;
                }
                case BpmScriptEvaluatePort.ScriptOutcome.KIND_SCRIPT_ERROR,
                     BpmScriptEvaluatePort.ScriptOutcome.KIND_TYPE_ERROR -> {
                    exec.setStatus("FAILED");
                    exec.setErrorText(BpmErrorCode.TRIGGER_SCRIPT_FAILED.getMessage() + ": "
                            + result.errorMessage());
                    insertExec(exec);
                    return;
                }
                default -> {
                    // OK：继续匹配
                }
            }
            Object value = result.value();
            String typeName = result.typeName();
            exec.setResultType(typeName);
            exec.setResultValue(value == null ? null : truncate(String.valueOf(value), 500));
            if (value == null) {
                exec.setStatus("UNMATCHED");
                exec.setDisposition(dispositionOf(trigger.getErrorDisposition()));
                exec.setErrorText(BpmErrorCode.TRIGGER_RESULT_UNMATCHED.getMessage() + ": 脚本返回 null");
                insertExec(exec);
                return;
            }
            TriggerConfig.TriggerBranch matched = matchBranch(trigger, typeName, value);
            if (matched == null) {
                exec.setStatus("UNMATCHED");
                exec.setDisposition(dispositionOf(trigger.getUnmatchedDisposition()));
                exec.setErrorText(BpmErrorCode.TRIGGER_RESULT_UNMATCHED.getMessage()
                        + ": 返回值 " + typeName + "=" + truncate(String.valueOf(value), 100)
                        + " 未命中任何分支");
                insertExec(exec);
                return;
            }
            exec.setStatus("MATCHED");
            exec.setMatchedBranchId(matched.getBranchId());
            insertExec(exec);
            dispatch(instance, trigger, matched, exec, snapshot.values());
        } catch (Exception e) {
            // 触发器评估异常不回滚业务办理：就地落可诊断 FAILED 行
            log.error("触发器评估异常: instance={}, trigger={}", instance.getProcessInstanceId(),
                    trigger.getTriggerId(), e);
            exec.setStatus("FAILED");
            exec.setErrorText("触发器评估异常: " + e.getMessage());
            exec.setDurationMs(elapsed(begin));
            insertExec(exec);
        }
    }

    private TriggerConfig.TriggerBranch matchBranch(TriggerConfig trigger, String typeName, Object value) {
        if (trigger.getBranches() == null) {
            return null;
        }
        for (TriggerConfig.TriggerBranch branch : trigger.getBranches()) {
            String branchType = branch.getMatchType() == null ? "" : branch.getMatchType().toUpperCase();
            if (!branchType.equals(typeName)) {
                continue;
            }
            if (valuesMatch(branchType, branch.getMatchValue(), value)) {
                return branch;
            }
        }
        return null;
    }

    private boolean valuesMatch(String typeName, String expected, Object actual) {
        try {
            return switch (typeName) {
                case "NUMBER" -> new java.math.BigDecimal(String.valueOf(actual))
                        .compareTo(new java.math.BigDecimal(expected)) == 0;
                case "BOOLEAN" -> Boolean.parseBoolean(expected) == (Boolean) actual;
                case "STRING" -> expected.equals(String.valueOf(actual));
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 派发（同事务登记可靠意图） ====================

    private void dispatch(BpmInstance instance, TriggerConfig trigger, TriggerConfig.TriggerBranch branch,
                          BpmTriggerExec exec, Map<String, Object> snapshot) {
        if (branch.getActions() == null || branch.getActions().isEmpty()) {
            triggerExecMapper.updateById(exec);
            return;
        }
        List<String> dispatchNotes = new ArrayList<>();
        for (ActionConfig action : branch.getActions()) {
            try {
                List<ActionItem> items = resolveItems(action, snapshot);
                int maxDispatch = resolveMaxDispatch(action);
                if (items.isEmpty()) {
                    dispatchNotes.add("动作 " + action.getActionId() + ": 派发集合为空，已阻止派发（EMPTY_COLLECTION）");
                    continue;
                }
                if (items.size() > maxDispatch) {
                    dispatchNotes.add("动作 " + action.getActionId() + ": 集合规模 " + items.size()
                            + " 超过上限 " + maxDispatch + "，已整体拒绝（OVER_LIMIT），未静默截断");
                    continue;
                }
                for (ActionItem item : items) {
                    dispatchItem(instance, trigger, action, exec, item, snapshot, dispatchNotes);
                }
            } catch (Exception e) {
                log.error("动作派发失败: instance={}, action={}", instance.getProcessInstanceId(),
                        action.getActionId(), e);
                dispatchNotes.add("动作 " + action.getActionId() + ": 派发失败 " + e.getMessage());
            }
        }
        if (!dispatchNotes.isEmpty()) {
            String existing = exec.getErrorText() == null ? "" : exec.getErrorText() + " | ";
            exec.setErrorText(existing + String.join(" | ", dispatchNotes));
        }
        triggerExecMapper.updateById(exec);
    }

    private void dispatchItem(BpmInstance instance, TriggerConfig trigger, ActionConfig action,
                              BpmTriggerExec exec, ActionItem item, Map<String, Object> snapshot,
                              List<String> notes) {
        String commandKey = "P64ACT:" + exec.getExecKey() + ":" + action.getActionId() + ":" + item.itemKey();
        Long tenantId = instance.getTenantId();
        Optional<BpmActionRef> existingRef = Optional.ofNullable(actionRefMapper.selectOne(
                Wrappers.<BpmActionRef>lambdaQuery()
                        .eq(BpmActionRef::getTenantId, tenantId)
                        .eq(BpmActionRef::getCommandKey, commandKey)
                        .last("limit 1")));
        if (existingRef.isPresent()) {
            // 同身份意图已登记：回查原命令，不重复入队
            notes.add("动作 " + action.getActionId() + " 项 " + item.itemKey() + ": 意图已存在（幂等回查）");
            return;
        }
        BpmActionRef ref = new BpmActionRef();
        ref.setExecId(exec.getId());
        ref.setProcessInstanceId(instance.getProcessInstanceId());
        ref.setTriggerId(trigger.getTriggerId());
        ref.setActionId(action.getActionId());
        ref.setActionType(action.getType());
        ref.setItemKey(truncate(item.itemKey(), 120));
        ref.setCommandKey(commandKey);
        ref.setTargetDefKey(action.getTargetProcessDefKey());
        ref.setTargetFormKey(action.getTargetFormKey());
        ref.setStatus("INTENT_SUBMITTED");
        actionRefMapper.insert(ref);

        Map<String, Object> data = new LinkedHashMap<>();
        if (action.getMapping() != null) {
            for (ActionConfig.ActionMapping mapping : action.getMapping()) {
                Object value;
                if (mapping.getLiteral() != null) {
                    value = mapping.getLiteral();
                } else if (mapping.getSourceVarId() != null && !mapping.getSourceVarId().isBlank()) {
                    value = snapshot.get(mapping.getSourceVarId());
                } else {
                    value = item.itemValues().get(mapping.getItemField());
                }
                data.put(mapping.getTargetField(), value);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refId", ref.getId());
        payload.put("commandKey", commandKey);
        payload.put("targetFormKey", action.getTargetFormKey());
        payload.put("targetDefKey", action.getTargetProcessDefKey());
        payload.put("sourceInstanceId", instance.getProcessInstanceId());
        payload.put("sourceBusinessKey", instance.getBusinessKey());
        payload.put("data", data);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("动作载荷序列化失败", e);
        }
        ref.setPayloadJson(payloadJson);
        ref.setItemSummary(safeJson(item.summary()));

        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.ORCH_ACTION_START);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey(commandKey);
        envelope.setTenantId(tenantId);
        envelope.setInitiatorId(instance.getInitiatorId());
        envelope.setPayload(payloadJson);
        envelope.setPayloadFingerprint(com.sw.ck.bpm.process.queue.CommandFingerprint.of(payloadJson));
        envelope.setCompletionPoint("ACTION_STARTED");
        try {
            Long commandId = commandQueue.enqueue(envelope);
            ref.setCommandId(commandId);
            notes.add("动作 " + action.getActionId() + " 项 " + item.itemKey()
                    + ": 意图已受理 commandId=" + commandId);
        } catch (DuplicateKeyException e) {
            // 同键并发受理：载荷一致才可吸收为幂等命中；异载荷显式冲突留痕，不冒称成功
            commandQueue.findByKey(tenantId, commandKey).ifPresentOrElse(existing -> {
                String incoming = com.sw.ck.bpm.process.queue.CommandFingerprint.of(payloadJson);
                String stored = existing.getPayloadFingerprint() != null
                        && !existing.getPayloadFingerprint().isBlank()
                        ? existing.getPayloadFingerprint()
                        : com.sw.ck.bpm.process.queue.CommandFingerprint.of(existing.getPayload());
                if (incoming.equals(stored)) {
                    notes.add("动作 " + action.getActionId() + " 项 " + item.itemKey()
                            + ": 并发重复受理已由幂等键吸收");
                } else {
                    notes.add("动作 " + action.getActionId() + " 项 " + item.itemKey()
                            + ": 同身份异载荷冲突（payload_fingerprint 不一致），未吸收为幂等命中");
                    ref.setErrorText("同身份异载荷冲突: commandKey=" + commandKey);
                }
            }, () -> notes.add("动作 " + action.getActionId() + " 项 " + item.itemKey()
                    + ": 并发重复受理已由幂等键吸收"));
        } catch (Exception e) {
            // 受理失败零残留（审查02 P1-06b）：意图行与命令同事务，命令未落即删除意图行，
            // 不留下无命令可恢复的孤儿 INTENT_SUBMITTED；失败经派发留痕可诊断
            actionRefMapper.deleteById(ref.getId());
            throw e;
        }
        actionRefMapper.updateById(ref);
    }

    // ==================== 集合解析 ====================

    @SuppressWarnings("unchecked")
    private List<ActionItem> resolveItems(ActionConfig action, Map<String, Object> snapshot) {
        String type = action.getType() == null ? "" : action.getType().toUpperCase();
        List<ActionItem> items = new ArrayList<>();
        if ("START_SINGLE".equals(type)) {
            Map<String, Object> emptyValues = Map.of();
            items.add(new ActionItem("SINGLE", Map.of("type", "SINGLE"), emptyValues));
            return items;
        }
        Object source = action.getSourceVariable() == null ? null : snapshot.get(action.getSourceVariable());
        if ("START_EACH".equals(type)) {
            if (source instanceof List<?> list) {
                int index = 0;
                for (Object item : list) {
                    index++;
                    if (item == null) {
                        continue;
                    }
                    if (item instanceof Map<?, ?> row) {
                        Map<String, Object> rowValues = new LinkedHashMap<>((Map<String, Object>) row);
                        Object rowId = rowValues.get("id");
                        String itemKey = rowId == null ? "ROW#" + index : String.valueOf(rowId);
                        items.add(new ActionItem(itemKey, Map.of("type", "ROW", "index", index), rowValues));
                    } else {
                        String id = String.valueOf(item);
                        items.add(new ActionItem(id, Map.of("type", "OBJECT", "id", id),
                                Map.of("id", id)));
                    }
                }
            }
            return items;
        }
        if ("START_GROUPED".equals(type)) {
            String groupBy = action.getGroupBy() == null ? "id" : action.getGroupBy();
            if (source instanceof List<?> list) {
                Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    if (item instanceof Map<?, ?> row) {
                        Map<String, Object> rowValues = new LinkedHashMap<>((Map<String, Object>) row);
                        Object key = rowValues.get(groupBy);
                        String groupKey = key == null ? "" : String.valueOf(key);
                        groups.putIfAbsent(groupKey, rowValues);
                    } else {
                        String id = String.valueOf(item);
                        groups.putIfAbsent(id, new LinkedHashMap<>(Map.of("id", id)));
                    }
                }
                groups.forEach((groupKey, values) -> items.add(new ActionItem(
                        groupKey == null || groupKey.isBlank() ? "GROUP" : groupKey,
                        Map.of("type", "GROUP", "groupBy", groupBy), values)));
            }
            return items;
        }
        return items;
    }

    private int resolveMaxDispatch(ActionConfig action) {
        int configured = action.getMaxDispatch() == null ? DEFAULT_MAX_DISPATCH : action.getMaxDispatch();
        return Math.min(Math.max(configured, 1), HARD_MAX_DISPATCH);
    }

    // ==================== 工具 ====================

    private String buildExecKey(BpmInstance instance, TriggerConfig trigger, String event,
                                long round, String scopeTaskId) {
        String base = "TRG:" + instance.getProcessInstanceId() + ":" + trigger.getTriggerId()
                + ":" + event + ":" + round;
        return scopeTaskId == null ? base : base + ":" + scopeTaskId;
    }

    private String dispositionOf(String configured) {
        return "IGNORE".equalsIgnoreCase(configured) ? "IGNORE" : "HALT";
    }

    private void insertExec(BpmTriggerExec exec) {
        try {
            triggerExecMapper.insert(exec);
        } catch (DuplicateKeyException e) {
            // 并发同事件重复评估：唯一键吸收，不重复记录
            log.info("触发器执行记录幂等冲突（并发重复评估）: execKey={}", exec.getExecKey());
        }
    }

    private void appendError(BpmTriggerExec exec, String message) {
        String existing = exec.getErrorText() == null ? "" : exec.getErrorText() + " | ";
        exec.setErrorText(existing + message);
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

    private long elapsed(long begin) {
        return System.currentTimeMillis() - begin;
    }
}
