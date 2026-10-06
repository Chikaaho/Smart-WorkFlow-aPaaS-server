package com.sw.ck.bpm.engine.delegate;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.delegate.FlowableCollectionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态并行分支集合解析（I4 §3.1）。
 * <p>
 * 进入节点时从受控来源解析部门集合，校验租户/部门有效性/负责人，
 * 经 {@link DynamicBranchPort} 冻结快照（幂等）后返回去重负责人集合。
 * 空集合、负责人缺失、失效/跨租户对象、超上限都给出确定结果：
 * 默认阻断，仅当节点配置显式选择受控策略（PROCEED/SKIP）时才放行，且逐条记录原因。
 * </p>
 */
@Component("dynamicBranchCollectionResolver")
public class DynamicBranchCollectionResolver extends NodeDelegateSupport
        implements FlowableCollectionHandler {

    static final int DEFAULT_MAX_BRANCHES = 50;
    private static final int HARD_MAX_BRANCHES = 200;

    private final DeptQueryFacade deptQueryFacade;
    private final UserQueryFacade userQueryFacade;
    private final ObjectProvider<DynamicBranchPort> branchPort;
    private final com.sw.ck.form.api.facade.FormRecordReadFacade formRecordReadFacade;

    public DynamicBranchCollectionResolver(RepositoryService repositoryService,
                                           com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                           ParticipantResolverRegistry participantResolverRegistry,
                                           DeptQueryFacade deptQueryFacade,
                                           UserQueryFacade userQueryFacade,
                                           ObjectProvider<DynamicBranchPort> branchPort,
                                           com.sw.ck.form.api.facade.FormRecordReadFacade formRecordReadFacade) {
        super(repositoryService, objectMapper, participantResolverRegistry);
        this.deptQueryFacade = deptQueryFacade;
        this.userQueryFacade = userQueryFacade;
        this.branchPort = branchPort;
        this.formRecordReadFacade = formRecordReadFacade;
    }

    @Override
    public Collection<?> resolveCollection(Object collection, DelegateExecution execution) {
        var flowElement = execution.getCurrentFlowElement();
        if (flowElement == null || flowElement.getId() == null || flowElement.getId().isBlank()) {
            throw new IllegalStateException("动态并行节点上下文缺失");
        }
        Map<String, Object> config = nodeConfigByKey(execution, flowElement.getId());
        // P63 G04：轮次冻结的唯一权威是"多实例根执行首次进入"。Flowable 7.1 的
        // executeOriginalBehavior 会在每个子实例执行上再次回调本解析器仅为取本实例元素，
        // 此时执行树尚未冻结该子 executionId——若按子 executionId 冻结会凭空开出业务新轮
        // 并把真实首入轮关闭（SUPERSEDED_BY_ROUND）。子实例调用一律爬升到多实例根执行，
        // 复用既有幂等口径（v1 按 tenant+instance+node；v2 按根 executionId），不重算不关旧轮。
        if (!execution.isMultiInstanceRoot()) {
            DelegateExecution root = execution;
            while (!root.isMultiInstanceRoot() && root.getParent() != null) {
                root = root.getParent();
            }
            if (root.isMultiInstanceRoot()) {
                execution = root;
            }
            // 找不到根（防御）：保持原路径解析，由端口层幂等兜底
        }
        Long tenantId = parseLong(execution.getVariable("tenantId"));
        if (tenantId == null) {
            throw new BaseException(BpmErrorCode.APPROVER_TENANT_ID_MISSING.getCode(),
                    BpmErrorCode.APPROVER_TENANT_ID_MISSING.getMessage());
        }
        if (isSemanticV2(config)) {
            return resolveCollectionV2(execution, config, tenantId);
        }
        int maxBranches = maxBranches(config);
        List<Long> deptIds = resolveDeptIds(execution, config);
        if (deptIds.size() > maxBranches) {
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_LIMIT_EXCEEDED.getCode(),
                    "动态并行来源部门数 " + deptIds.size() + " 超过上限 " + maxBranches);
        }
        if (deptIds.isEmpty()) {
            if ("PROCEED".equalsIgnoreCase(asString(config.get("emptyStrategy")))) {
                freeze(execution, config, tenantId, List.of());
                return List.of(); // 显式受控放行：零分支，节点直接完成
            }
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getCode(),
                    BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getMessage());
        }

        List<DynamicBranchPort.BranchCandidate> candidates = new ArrayList<>();
        List<Long> invalid = new ArrayList<>();
        // deptIds 已在入口保证非空：findActiveDeptIds 的 empty 只在查询对象缺失时产生，属契约违约
        List<Long> active = deptQueryFacade.findActiveDeptIds(deptIds).orElseThrow(
                () -> new IllegalStateException("部门查询上下文缺失，无法解析动态并行来源: " + deptIds));
        var activeDeptIds = new java.util.HashSet<>(active);
        for (Long deptId : deptIds) {
            if (!activeDeptIds.contains(deptId)) {
                invalid.add(deptId);
                candidates.add(new DynamicBranchPort.BranchCandidate(
                        String.valueOf(deptId), null, "DEPT_INVALID"));
                continue;
            }
            // 单部门非空 + 显式 tenantId（入口已校验）⇒ 查询上下文完整；
            // empty 按负责人缺失的确定结果处置，与迁移前的空列表口径一致
            Optional<List<Long>> leaderIds = userQueryFacade.findActiveUserIdsByDeptLeaders(
                    List.of(deptId), tenantId);
            List<Long> leaders = leaderIds.isEmpty() ? List.of() : leaderIds.orElseThrow();
            if (leaders.isEmpty()) {
                invalid.add(deptId);
                candidates.add(new DynamicBranchPort.BranchCandidate(
                        String.valueOf(deptId), null, "LEADER_MISSING"));
                continue;
            }
            // 同一部门多负责人：取最小用户 ID（可解释稳定规则）
            candidates.add(new DynamicBranchPort.BranchCandidate(
                    String.valueOf(deptId), String.valueOf(leaders.stream().min(Long::compare).orElseThrow()),
                    null));
        }
        if (!invalid.isEmpty() && !"SKIP".equalsIgnoreCase(asString(config.get("invalidStrategy")))) {
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_LEADER_MISSING.getCode(),
                    "部门 " + invalid + " 失效或负责人缺失");
        }
        List<DynamicBranchPort.FrozenBranch> frozen = freeze(execution, config, tenantId, candidates);
        if (frozen.size() > maxBranches) {
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_LIMIT_EXCEEDED.getCode(),
                    "动态并行分支数 " + frozen.size() + " 超过上限 " + maxBranches);
        }
        return frozen.stream().map(DynamicBranchPort.FrozenBranch::leaderId).toList();
    }

    @SuppressWarnings("unchecked")
    private List<DynamicBranchPort.FrozenBranch> freeze(DelegateExecution execution,
                                                        Map<String, Object> config, Long tenantId,
                                                        List<DynamicBranchPort.BranchCandidate> candidates) {
        DynamicBranchPort port = branchPort.getIfAvailable();
        if (port == null) {
            throw new BaseException(BpmErrorCode.TRANSLATION_FAILED.getCode(),
                    "动态分支冻结端口不可用");
        }
        Map<String, Object> source = config.get("source") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        // 冻结成功但候选全部无效时为空列表（合法零结果），契约恒 present
        return port.freeze(String.valueOf(tenantId), execution.getProcessInstanceId(),
                execution.getCurrentActivityId(),
                asString(source.get("type")), asString(source.get("value")),
                asString(config.getOrDefault("mode", "ALL")), candidates).orElseThrow(
                        () -> new IllegalStateException("动态分支冻结未返回结果"));
    }

    /** P63 语义版本 2：对象身份分支 + 轮次 + 来源行追溯；缺省/1=旧语义。 */
    private boolean isSemanticV2(Map<String, Object> config) {
        Object version = config.get("semanticVersion");
        try {
            return version != null && Integer.parseInt(String.valueOf(version)) >= 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * v2 路径：按对象身份（USER/DEPT）从主字段/表格列/变量/固定值收集来源对象与位置，
     * 经 freezeRound 以多实例根 executionId 冻结轮次；同负责人不同部门保持独立分支。
     */
    @SuppressWarnings("unchecked")
    private Collection<?> resolveCollectionV2(DelegateExecution execution, Map<String, Object> config,
                                              Long tenantId) {
        int maxBranches = maxBranches(config);
        Map<String, Object> source = config.get("source") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        boolean userObjects = "USER".equalsIgnoreCase(asString(source.get("objectType")));
        boolean tableScope = "TABLE".equalsIgnoreCase(asString(source.get("scope")));

        // 收集对象 ID → 来源位置（LinkedHashMap 保序去重）
        LinkedHashMap<String, List<String>> objectRefs = new LinkedHashMap<>();
        if ("FIXED".equalsIgnoreCase(asString(source.get("type")))) {
            for (String id : splitIds(source.get("value"))) {
                objectRefs.computeIfAbsent(id, key -> new ArrayList<>())
                        .add(refJson("FIXED", asString(source.get("field")), null, null));
            }
        } else if (tableScope) {
            String recordId = asString(execution.getVariable("recordId"));
            String formKey = asString(execution.getVariable("formKey"));
            FormRecordReadFacade.FormRecordData record = formRecordReadFacade
                    .findRecord(tenantId, formKey, recordId)
                    .orElseThrow(() -> new BaseException(BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getCode(),
                            "实例表单记录不存在或已删除，无法解析动态分支来源"));
            List<Map<String, Object>> rows = record.tables()
                    .getOrDefault(asString(source.get("tableField")), List.of());
            String column = asString(source.get("column"));
            for (Map<String, Object> row : rows) {
                Object rowId = row.get("id");
                collectObjectIds(row.get(column), id -> objectRefs
                        .computeIfAbsent(id, key -> new ArrayList<>())
                        .add(refJson("TABLE_ROW", asString(source.get("field")),
                                asString(source.get("tableField")),
                                rowId == null ? null : String.valueOf(rowId))));
            }
        } else if ("VARIABLE".equalsIgnoreCase(asString(source.get("type")))) {
            for (String id : splitIds(execution.getVariable(asString(source.get("value"))))) {
                objectRefs.computeIfAbsent(id, key -> new ArrayList<>())
                        .add(refJson("VARIABLE", asString(source.get("value")), null, null));
            }
        } else {
            // FORM_FIELD：从实例表单数据权威读取（含多选解码），不用发起时变量快照
            String recordId = asString(execution.getVariable("recordId"));
            String formKey = asString(execution.getVariable("formKey"));
            FormRecordReadFacade.FormRecordData record = formRecordReadFacade
                    .findRecord(tenantId, formKey, recordId)
                    .orElseThrow(() -> new BaseException(BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getCode(),
                            "实例表单记录不存在或已删除，无法解析动态分支来源"));
            collectObjectIds(record.fields().get(asString(source.get("value"))),
                    id -> objectRefs.computeIfAbsent(id, key -> new ArrayList<>())
                            .add(refJson("FIELD", asString(source.get("value")), null, null)));
        }

        if (objectRefs.size() > maxBranches) {
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_LIMIT_EXCEEDED.getCode(),
                    "动态并行来源对象数 " + objectRefs.size() + " 超过上限 " + maxBranches);
        }
        if (objectRefs.isEmpty()) {
            if ("PROCEED".equalsIgnoreCase(asString(config.get("emptyStrategy")))) {
                freezeRound(execution, config, tenantId, userObjects, List.of());
                return List.of(); // 显式受控放行：零分支，节点直接完成
            }
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getCode(),
                    BpmErrorCode.DYNAMIC_BRANCH_EMPTY.getMessage());
        }

        // 逐对象解析办理人：USER=本人；DEPT=服务端权威解析唯一负责人
        List<DynamicBranchPort.BranchCandidateV2> candidates = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (userObjects) {
            List<Long> parsed = objectRefs.keySet().stream().map(Long::valueOf).toList();
            List<Long> active = userQueryFacade.findActiveUserIds(parsed, tenantId)
                    .orElse(List.of());
            var activeSet = new java.util.HashSet<>(active);
            for (Map.Entry<String, List<String>> entry : objectRefs.entrySet()) {
                if (activeSet.contains(Long.valueOf(entry.getKey()))) {
                    candidates.add(new DynamicBranchPort.BranchCandidateV2(entry.getKey(),
                            entry.getKey(), null, refListJson(entry.getValue())));
                } else {
                    invalid.add(entry.getKey());
                    candidates.add(new DynamicBranchPort.BranchCandidateV2(entry.getKey(),
                            null, "OBJECT_INVALID", refListJson(entry.getValue())));
                }
            }
        } else {
            List<Long> deptIds = objectRefs.keySet().stream().map(Long::valueOf).toList();
            var activeSet = new java.util.HashSet<>(
                    deptQueryFacade.findActiveDeptIds(deptIds).orElse(List.of()));
            Map<Long, Long> leaderMap = userQueryFacade.findDeptLeaderMap(deptIds, tenantId)
                    .orElse(Map.of());
            for (Map.Entry<String, List<String>> entry : objectRefs.entrySet()) {
                long deptId = Long.parseLong(entry.getKey());
                if (!activeSet.contains(deptId)) {
                    invalid.add(entry.getKey());
                    candidates.add(new DynamicBranchPort.BranchCandidateV2(entry.getKey(),
                            null, "OBJECT_INVALID", refListJson(entry.getValue())));
                } else if (!leaderMap.containsKey(deptId)) {
                    invalid.add(entry.getKey());
                    candidates.add(new DynamicBranchPort.BranchCandidateV2(entry.getKey(),
                            null, "LEADER_MISSING", refListJson(entry.getValue())));
                } else {
                    candidates.add(new DynamicBranchPort.BranchCandidateV2(entry.getKey(),
                            String.valueOf(leaderMap.get(deptId)), null, refListJson(entry.getValue())));
                }
            }
        }
        if (!invalid.isEmpty() && !"SKIP".equalsIgnoreCase(asString(config.get("invalidStrategy")))) {
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_LEADER_MISSING.getCode(),
                    (userObjects ? "人员 " : "部门 ") + invalid + " 失效或负责人缺失");
        }
        List<DynamicBranchPort.FrozenBranchV2> frozen = freezeRound(execution, config, tenantId,
                userObjects, candidates);
        if (frozen.size() > maxBranches) {
            throw new BaseException(BpmErrorCode.DYNAMIC_BRANCH_LIMIT_EXCEEDED.getCode(),
                    "动态并行分支数 " + frozen.size() + " 超过上限 " + maxBranches);
        }
        return frozen.stream().map(DynamicBranchPort.FrozenBranchV2::assigneeId).toList();
    }

    private List<DynamicBranchPort.FrozenBranchV2> freezeRound(DelegateExecution execution,
                                                               Map<String, Object> config,
                                                               Long tenantId, boolean userObjects,
                                                               List<DynamicBranchPort.BranchCandidateV2> candidates) {
        DynamicBranchPort port = branchPort.getIfAvailable();
        if (port == null) {
            throw new BaseException(BpmErrorCode.TRANSLATION_FAILED.getCode(), "动态分支冻结端口不可用");
        }
        Map<String, Object> source = config.get("source") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        // 新轮次开启时重置汇聚计票（同轮恢复复放同样安全：任务尚未建、票数应为 0）
        execution.setVariable("consensusApprovedCount", 0);
        execution.setVariable("consensusRejectedCount", 0);
        return port.freezeRound(String.valueOf(tenantId), execution.getProcessInstanceId(),
                execution.getCurrentActivityId(), execution.getId(),
                asString(source.get("type")),
                asString(source.get("value")),
                asString(config.getOrDefault("mode", "ALL")),
                userObjects ? "USER" : "DEPT", candidates).orElseThrow(
                        () -> new IllegalStateException("动态分支轮次冻结未返回结果"));
    }

    /** 单值/多选列表/JSON 数组串统一收集稳定对象 ID。 */
    private void collectObjectIds(Object value, java.util.function.Consumer<String> sink) {
        for (String id : splitIds(value)) {
            sink.accept(id);
        }
    }

    /** 值 → 正整数 ID 列表（单值、列表、JSON 数组串、逗号分隔串）。 */
    private List<String> splitIds(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Object> flattened = new ArrayList<>();
        if (value instanceof String text && text.trim().startsWith("[")) {
            try {
                List<?> decoded = objectMapper.readValue(text,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() { });
                flattened.addAll(decoded);
            } catch (Exception e) {
                flattened.add(value);
            }
        } else if (value instanceof Collection<?> collection) {
            flattened.addAll(collection);
        } else {
            flattened.add(value);
        }
        List<String> ids = new ArrayList<>();
        for (Object item : flattened) {
            if (item == null) continue;
            String text = String.valueOf(item).trim();
            if (text.contains(",")) {
                for (String part : text.split(",")) {
                    addPositiveId(part.trim(), ids);
                }
            } else {
                addPositiveId(text, ids);
            }
        }
        return ids;
    }

    private void addPositiveId(String text, List<String> sink) {
        if (text.matches("\\d+") && Long.parseLong(text) > 0 && !sink.contains(text)) {
            sink.add(text);
        }
    }

    private String refJson(String kind, String field, String tableField, String rowId) {
        try {
            LinkedHashMap<String, Object> ref = new LinkedHashMap<>();
            ref.put("kind", kind);
            if (field != null) ref.put("field", field);
            if (tableField != null) ref.put("tableField", tableField);
            if (rowId != null) ref.put("rowId", rowId);
            return objectMapper.writeValueAsString(List.of(ref));
        } catch (Exception e) {
            return "[]";
        }
    }

    private String refListJson(List<String> refs) {
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 来源解析：FIXED=配置值；VARIABLE/FORM_FIELD=流程变量（表单记录则按键取字段）。 */
    private List<Long> resolveDeptIds(DelegateExecution execution, Map<String, Object> config) {
        Map<?, ?> source = config.get("source") instanceof Map<?, ?> raw ? raw : Map.of();
        String type = asString(source.get("type"));
        Object value = source.get("value");
        if ("FIXED".equalsIgnoreCase(type)) {
            return toDeptIds(value);
        }
        Object resolved = execution.getVariable(asString(value));
        if (resolved == null) {
            // 表单记录可能以整包 Map 存储（record/formData），按键取字段
            for (String bagKey : List.of("record", "formData", "form")) {
                Object bag = execution.getVariable(bagKey);
                if (bag instanceof Map<?, ?> map && map.containsKey(String.valueOf(value))) {
                    resolved = map.get(String.valueOf(value));
                    break;
                }
            }
        }
        return toDeptIds(resolved);
    }

    private List<Long> toDeptIds(Object value) {
        Collection<?> values = value instanceof Collection<?> collection
                ? collection : value == null ? List.of() : List.of(value);
        // 支持逗号分隔字符串来源
        List<Object> flattened = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String text && text.contains(",")) {
                for (String part : text.split(",")) {
                    flattened.add(part.trim());
                }
            } else {
                flattened.add(item);
            }
        }
        return flattened.stream().map(item -> {
            try {
                return Long.valueOf(String.valueOf(item).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }).filter(item -> item != null && item > 0).distinct().sorted().toList();
    }

    private int maxBranches(Map<String, Object> config) {
        try {
            return Math.min(HARD_MAX_BRANCHES, Math.max(1,
                    Integer.parseInt(String.valueOf(config.getOrDefault("maxBranches",
                            DEFAULT_MAX_BRANCHES)))));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_BRANCHES;
        }
    }

    private Map<String, Object> nodeConfigByKey(DelegateExecution execution, String nodeKey) {
        var model = repositoryService.getBpmnModel(execution.getProcessDefinitionId());
        var element = model == null ? null : model.getFlowElement(nodeKey);
        String json = element == null ? null : element.getAttributeValue(FLOWABLE_NS, "nodeConfig");
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            throw new BaseException(BpmErrorCode.NODE_CONFIG_INVALID.getCode(), "动态并行配置读取失败");
        }
    }
}
