package com.sw.ck.bpm.engine.delegate;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
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

    public DynamicBranchCollectionResolver(RepositoryService repositoryService,
                                           com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                           ParticipantResolverRegistry participantResolverRegistry,
                                           DeptQueryFacade deptQueryFacade,
                                           UserQueryFacade userQueryFacade,
                                           ObjectProvider<DynamicBranchPort> branchPort) {
        super(repositoryService, objectMapper, participantResolverRegistry);
        this.deptQueryFacade = deptQueryFacade;
        this.userQueryFacade = userQueryFacade;
        this.branchPort = branchPort;
    }

    @Override
    public Collection<?> resolveCollection(Object collection, DelegateExecution execution) {
        var flowElement = execution.getCurrentFlowElement();
        if (flowElement == null || flowElement.getId() == null || flowElement.getId().isBlank()) {
            throw new IllegalStateException("动态并行节点上下文缺失");
        }
        Map<String, Object> config = nodeConfigByKey(execution, flowElement.getId());
        Long tenantId = parseLong(execution.getVariable("tenantId"));
        if (tenantId == null) {
            throw new BaseException(BpmErrorCode.APPROVER_TENANT_ID_MISSING.getCode(),
                    BpmErrorCode.APPROVER_TENANT_ID_MISSING.getMessage());
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
