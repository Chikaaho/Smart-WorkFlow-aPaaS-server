package com.sw.ck.bpm.process.validator;

import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P64 变量/触发器/节点表单发布校验（ADR-P64-001 §2/§3/§4；挂在图校验之后、冻结之前）。
 * <p>
 * 校验失败发布拒绝（fail closed，可纠正）；校验通过后配置随 graph_json 冻结，运行期按
 * 冻结版本解析。无 variables/triggers/节点表单绑定的旧图零校验、零行为。
 * </p>
 */
@Slf4j
@Component
public class ProcessVariableValidator {

    private static final Set<String> VARIABLE_TYPES = Set.of(
            "NUMBER", "STRING", "BOOLEAN", "USER", "DEPT", "USER_SET", "DEPT_SET", "ROWS");
    private static final Set<String> VARIABLE_SOURCES = Set.of("MAIN_FORM", "NODE_FORM", "SYSTEM");
    private static final Set<String> AGGREGATIONS = Set.of("NONE", "UNION", "CONCAT");
    private static final Set<String> TRIGGER_EVENTS = Set.of(
            "TASK_SUBMITTED", "NODE_ROUND_COMPLETED", "PROCESS_COMPLETED");
    private static final Set<String> ACTION_TYPES = Set.of(
            "START_SINGLE", "START_EACH", "START_GROUPED");
    private static final Set<String> NODE_FORM_CAPABLE_TYPES = Set.of(
            "APPROVAL", "CONSENSUS", "DYNAMIC_PARALLEL");
    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "processInstanceId", "processDefKey", "businessKey", "formKey",
            "initiatorId", "tenantId", "currentNodeKey", "roundNo");
    private static final Set<String> MATCH_TYPES = Set.of("NUMBER", "STRING", "BOOLEAN");

    private final FormDefinitionService formDefinitionService;
    private final BpmFormBindingService formBindingService;
    private final BpmScriptEvaluatePort scriptRunner;
    private final ObjectMapper objectMapper;

    public ProcessVariableValidator(FormDefinitionService formDefinitionService,
                                    BpmFormBindingService formBindingService,
                                    BpmScriptEvaluatePort scriptRunner,
                                    ObjectMapper objectMapper) {
        this.formDefinitionService = formDefinitionService;
        this.formBindingService = formBindingService;
        this.scriptRunner = scriptRunner;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布期校验（图内变量、触发器、节点表单绑定一致性）。
     *
     * @return 错误列表；空 = 通过
     */
    public List<GraphValidationError> validate(ProcessGraph graph) {
        List<GraphValidationError> errors = new ArrayList<>();
        if (graph == null) {
            return errors;
        }
        validateNodeFormBindings(graph, errors);
        validateVariables(graph, errors);
        validateTriggers(graph, errors);
        validateWaitNodes(graph, errors);
        return errors;
    }

    // ==================== 子流程等待节点（P64 阶段Ⅱ A05） ====================

    /** 等待节点引用校验：引用的动作必须存在、是 CHILD 且等待策略非 NONE（NONE 无等待语义）。 */
    private void validateWaitNodes(ProcessGraph graph, List<GraphValidationError> errors) {
        if (graph.getElements() == null) {
            return;
        }
        Set<String> waitableActionIds = new HashSet<>();
        if (graph.getTriggers() != null) {
            for (TriggerConfig trigger : graph.getTriggers()) {
                if (trigger.getBranches() == null) {
                    continue;
                }
                for (TriggerConfig.TriggerBranch branch : trigger.getBranches()) {
                    if (branch.getActions() == null) {
                        continue;
                    }
                    for (ActionConfig action : branch.getActions()) {
                        if (action.isChild()
                                && !"NONE".equalsIgnoreCase(action.getWaitPolicy())) {
                            waitableActionIds.add(action.getActionId());
                        }
                    }
                }
            }
        }
        for (var element : graph.getElements()) {
            if (!"node".equals(element.getKind())
                    || !"SUBFLOW_WAIT".equalsIgnoreCase(element.getType())) {
                continue;
            }
            Object refsObj = element.getConfig() == null ? null
                    : element.getConfig().get("waitActionIds");
            if (refsObj == null) {
                // 缺省 = 等待本图全部等待型 CHILD 动作：仅当图内存在可等动作时合法
                if (waitableActionIds.isEmpty()) {
                    errors.add(error(element.getId(), BpmErrorCode.WAIT_NODE_CONFIG_INVALID,
                            "子流程等待节点未配置 waitActionIds，且图中没有 ALL/ANY/COUNT 策略的子流程动作可等待"));
                }
                continue;
            }
            if (!(refsObj instanceof List<?> refs)) {
                errors.add(error(element.getId(), BpmErrorCode.WAIT_NODE_CONFIG_INVALID,
                        "waitActionIds 必须是动作 ID 数组"));
                continue;
            }
            for (Object ref : refs) {
                if (!waitableActionIds.contains(String.valueOf(ref))) {
                    errors.add(error(element.getId(), BpmErrorCode.WAIT_NODE_CONFIG_INVALID,
                            "等待节点引用的动作不存在、不是子流程动作或等待策略为 NONE: " + ref));
                }
            }
        }
    }

    // ==================== 节点表单绑定 ====================

    private void validateNodeFormBindings(ProcessGraph graph, List<GraphValidationError> errors) {
        if (graph.getElements() == null) {
            return;
        }
        for (var element : graph.getElements()) {
            if (!"node".equals(element.getKind()) || element.getConfig() == null) {
                continue;
            }
            Object nodeFormObj = element.getConfig().get("nodeForm");
            if (!(nodeFormObj instanceof Map<?, ?> nodeForm) || nodeForm.isEmpty()) {
                continue;
            }
            String type = element.getType() == null ? "" : element.getType().toUpperCase();
            if (!NODE_FORM_CAPABLE_TYPES.contains(type)) {
                errors.add(error(element.getId(), BpmErrorCode.NODE_FORM_NOT_BOUND,
                        "节点类型 " + type + " 不支持绑定业务表单（仅 APPROVAL/CONSENSUS/DYNAMIC_PARALLEL）"));
                continue;
            }
            Object formKeyObj = nodeForm.get("formKey");
            if (formKeyObj == null || String.valueOf(formKeyObj).isBlank()) {
                errors.add(error(element.getId(), BpmErrorCode.NODE_FORM_NOT_BOUND,
                        "节点业务表单绑定缺少 formKey"));
                continue;
            }
            String formKey = String.valueOf(formKeyObj).trim();
            Optional<FormDefDTO> def = formDefinitionService.getFormDef(formKey);
            if (def.isEmpty() || !"PUBLISHED".equals(def.get().getStatus())) {
                errors.add(error(element.getId(), BpmErrorCode.NODE_FORM_NOT_BOUND,
                        "节点业务表单不存在或未发布: " + formKey));
            }
        }
    }

    // ==================== 变量 ====================

    private void validateVariables(ProcessGraph graph, List<GraphValidationError> errors) {
        List<ProcessVariableDef> variables = graph.getVariables();
        if (variables == null || variables.isEmpty()) {
            return;
        }
        Set<String> varIds = new HashSet<>();
        // 节点表单绑定索引（nodeKey → formKey）
        Map<String, String> nodeForms = new java.util.HashMap<>();
        if (graph.getElements() != null) {
            for (var element : graph.getElements()) {
                if ("node".equals(element.getKind()) && element.getConfig() != null
                        && element.getConfig().get("nodeForm") instanceof Map<?, ?> nodeForm
                        && nodeForm.get("formKey") != null) {
                    nodeForms.put(element.getId(), String.valueOf(nodeForm.get("formKey")));
                }
            }
        }
        for (ProcessVariableDef def : variables) {
            String locator = def.getVarId();
            if (def.getVarId() == null || def.getVarId().isBlank()) {
                errors.add(error(null, BpmErrorCode.VARIABLE_INVALID, "变量缺少稳定引用 varId"));
                continue;
            }
            if (!varIds.add(def.getVarId())) {
                errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                        "变量 varId 重复: " + def.getVarId()));
                continue;
            }
            if (def.getType() == null || !VARIABLE_TYPES.contains(def.getType().toUpperCase())) {
                errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                        "变量 " + def.getVarId() + " 类型无效: " + def.getType()));
                continue;
            }
            String source = def.getSource() == null ? "" : def.getSource().toUpperCase();
            if (!VARIABLE_SOURCES.contains(source)) {
                errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                        "变量 " + def.getVarId() + " 来源无效: " + def.getSource()));
                continue;
            }
            String type = def.getType().toUpperCase();
            switch (source) {
                case "MAIN_FORM" -> {
                    if (def.getSourceField() == null || def.getSourceField().isBlank()) {
                        errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                                "变量 " + def.getVarId() + ": MAIN_FORM 来源缺少 sourceField"));
                        continue;
                    }
                    validateMainFormFieldType(graph.getFormKey(), def, type, errors);
                }
                case "NODE_FORM" -> {
                    String nodeKey = def.getSourceNodeKey();
                    if (nodeKey == null || nodeKey.isBlank()) {
                        errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                                "变量 " + def.getVarId() + ": NODE_FORM 来源缺少 sourceNodeKey"));
                        continue;
                    }
                    String boundFormKey = nodeForms.get(nodeKey);
                    if (boundFormKey == null) {
                        errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                                "变量 " + def.getVarId() + ": 来源节点 " + nodeKey + " 未绑定业务表单"));
                        continue;
                    }
                    if (def.getSourceFormField() == null || def.getSourceFormField().isBlank()) {
                        errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                                "变量 " + def.getVarId() + ": NODE_FORM 来源缺少 sourceFormField"));
                        continue;
                    }
                    validateNodeFormFieldType(boundFormKey, def, type, nodeKey, errors);
                }
                case "SYSTEM" -> {
                    if (def.getSourceField() == null || !SYSTEM_FIELDS.contains(def.getSourceField())) {
                        errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                                "变量 " + def.getVarId() + ": 系统变量白名单外字段 " + def.getSourceField()));
                    }
                }
                default -> {
                    // 已在上/source 无效分支处理
                }
            }
            String aggregation = def.getAggregation() == null ? "NONE" : def.getAggregation().toUpperCase();
            if (!AGGREGATIONS.contains(aggregation)) {
                errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                        "变量 " + def.getVarId() + " 聚合规则无效: " + def.getAggregation()));
            }
            if (("USER_SET".equals(type) || "DEPT_SET".equals(type))
                    && !"UNION".equals(aggregation)) {
                errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                        "变量 " + def.getVarId() + ": 集合类型必须配置 UNION 聚合"));
            }
        }
    }

    /** 主表单字段类型相容性（不隐式转换；不可解释组合发布拒绝）。 */
    private void validateMainFormFieldType(String formKey, ProcessVariableDef def,
                                           String type, List<GraphValidationError> errors) {
        Optional<String> definitionJson = formDefinitionService.getFormDefinition(formKey);
        if (definitionJson.isEmpty()) {
            errors.add(error(def.getVarId(), BpmErrorCode.VARIABLE_INVALID,
                    "变量 " + def.getVarId() + ": 主业务表单定义不可用 " + formKey));
            return;
        }
        Map<String, Object> fieldType = findField(definitionJson.get(), def.getSourceField(), null);
        if (fieldType == null) {
            errors.add(error(def.getVarId(), BpmErrorCode.VARIABLE_INVALID,
                    "变量 " + def.getVarId() + ": 主业务表单缺少字段 " + def.getSourceField()));
            return;
        }
        assertTypeCompatible(def, type, String.valueOf(fieldType.get("type")),
                fieldFlag(fieldType, "multiple"), errors);
    }

    private void validateNodeFormFieldType(String boundFormKey, ProcessVariableDef def, String type,
                                           String nodeKey, List<GraphValidationError> errors) {
        Optional<String> definitionJson = formDefinitionService.getFormDefinition(boundFormKey);
        if (definitionJson.isEmpty()) {
            errors.add(error(def.getVarId(), BpmErrorCode.VARIABLE_INVALID,
                    "变量 " + def.getVarId() + ": 节点表单定义不可用 " + boundFormKey));
            return;
        }
        Map<String, Object> fieldType = findField(definitionJson.get(), def.getSourceFormField(), null);
        if (fieldType == null) {
            errors.add(error(def.getVarId(), BpmErrorCode.VARIABLE_INVALID,
                    "变量 " + def.getVarId() + ": 节点表单缺少字段 " + def.getSourceFormField()));
            return;
        }
        assertTypeCompatible(def, type, String.valueOf(fieldType.get("type")),
                fieldFlag(fieldType, "multiple"), errors);
    }

    private void assertTypeCompatible(ProcessVariableDef def, String variableType,
                                      String formFieldType, boolean multiple,
                                      List<GraphValidationError> errors) {
        String ff = formFieldType == null ? "" : formFieldType.toUpperCase();
        boolean compatible = switch (variableType) {
            case "NUMBER" -> "NUMBER".equals(ff);
            case "BOOLEAN" -> "BOOL".equals(ff);
            case "STRING" -> ff.equals("TEXT") || ff.equals("RICH_TEXT") || ff.equals("DATE")
                    || ff.equals("TIME") || ff.equals("DICT") || ff.equals("MULTISELECT")
                    || ff.equals("REFERENCE") || ff.equals("LABEL");
            case "USER" -> "USER".equals(ff) && !multiple;
            case "DEPT" -> "DEPT".equals(ff) && !multiple;
            case "USER_SET" -> "USER".equals(ff);
            case "DEPT_SET" -> "DEPT".equals(ff);
            case "ROWS" -> "TABLE".equals(ff);
            default -> false;
        };
        if (!compatible) {
            errors.add(error(def.getVarId(), BpmErrorCode.VARIABLE_INVALID,
                    "变量 " + def.getVarId() + ": 声明类型 " + variableType
                            + " 与来源字段类型 " + ff + (multiple ? "(多选)" : "") + " 不相容（不做隐式转换）"));
        }
    }

    // ==================== 触发器 ====================

    private void validateTriggers(ProcessGraph graph, List<GraphValidationError> errors) {
        List<TriggerConfig> triggers = graph.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            return;
        }
        Set<String> varIds = new HashSet<>();
        if (graph.getVariables() != null) {
            graph.getVariables().forEach(def -> varIds.add(def.getVarId()));
        }
        Set<String> nodeKeys = new HashSet<>();
        if (graph.getElements() != null) {
            graph.getElements().stream()
                    .filter(element -> "node".equals(element.getKind()))
                    .forEach(element -> nodeKeys.add(element.getId()));
        }
        Set<String> triggerIds = new HashSet<>();
        for (TriggerConfig trigger : triggers) {
            String locator = trigger.getTriggerId();
            if (trigger.getTriggerId() == null || trigger.getTriggerId().isBlank()) {
                errors.add(error(null, BpmErrorCode.TRIGGER_INVALID, "触发器缺少稳定 ID"));
                continue;
            }
            if (!triggerIds.add(trigger.getTriggerId())) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 ID 重复: " + trigger.getTriggerId()));
                continue;
            }
            String event = trigger.getEvent() == null ? "" : trigger.getEvent().toUpperCase();
            if (!TRIGGER_EVENTS.contains(event)) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 事件无效: " + trigger.getEvent()));
                continue;
            }
            if (!"PROCESS_COMPLETED".equals(event)) {
                if (trigger.getNodeKey() == null || trigger.getNodeKey().isBlank()) {
                    errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                            "触发器 " + trigger.getTriggerId() + ": 该事件必须绑定 sourceNodeKey"));
                    continue;
                }
                if (!nodeKeys.contains(trigger.getNodeKey())) {
                    errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                            "触发器 " + trigger.getTriggerId() + ": 源节点不存在 " + trigger.getNodeKey()));
                    continue;
                }
            }
            if (trigger.getScript() == null || trigger.getScript().isBlank()) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + ": 判断脚本为空"));
                continue;
            }
            BpmScriptEvaluatePort.ScriptOutcome scriptCheck = scriptRunner.validate(trigger.getScript())
                    .orElseThrow(() -> new IllegalStateException(
                            "BpmScriptEvaluatePort#validate 契约恒 present，empty 属契约违约"));
            if (!scriptCheck.ok()) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 脚本不可编译: " + scriptCheck.errorMessage()));
                continue;
            }
            if (trigger.getVariables() != null) {
                for (String varId : trigger.getVariables()) {
                    if (!varIds.contains(varId)) {
                        errors.add(error(locator, BpmErrorCode.VARIABLE_INVALID,
                                "触发器 " + trigger.getTriggerId() + " 授权了未定义变量: " + varId));
                    }
                }
            }
            validateBranches(graph, trigger, errors);
        }
    }

    private void validateBranches(ProcessGraph graph, TriggerConfig trigger, List<GraphValidationError> errors) {
        if (trigger.getBranches() == null) {
            return;
        }
        Set<String> branchIds = new HashSet<>();
        // 同类型同值重复分支发布拒绝（方向 §3.2）
        Set<String> seenMatchKeys = new HashSet<>();
        for (TriggerConfig.TriggerBranch branch : trigger.getBranches()) {
            String locator = trigger.getTriggerId() + ":" + branch.getBranchId();
            if (branch.getBranchId() == null || branch.getBranchId().isBlank()) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 分支缺少 ID"));
                continue;
            }
            if (!branchIds.add(branch.getBranchId())) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 分支 ID 重复: " + branch.getBranchId()));
                continue;
            }
            String matchType = branch.getMatchType() == null ? "" : branch.getMatchType().toUpperCase();
            if (!MATCH_TYPES.contains(matchType)) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 分支匹配类型无效: " + branch.getMatchType()));
                continue;
            }
            if (branch.getMatchValue() == null || branch.getMatchValue().isBlank()) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 分支匹配值为空"));
                continue;
            }
            if (matchType.equals("NUMBER")) {
                try {
                    new java.math.BigDecimal(branch.getMatchValue());
                } catch (Exception e) {
                    errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                            "触发器 " + trigger.getTriggerId() + " 分支匹配值不是数字: " + branch.getMatchValue()));
                    continue;
                }
            }
            if (matchType.equals("BOOLEAN")
                    && !"true".equalsIgnoreCase(branch.getMatchValue())
                    && !"false".equalsIgnoreCase(branch.getMatchValue())) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 布尔分支匹配值无效: " + branch.getMatchValue()));
                continue;
            }
            if (!seenMatchKeys.add(matchType + "=" + branch.getMatchValue().trim())) {
                errors.add(error(locator, BpmErrorCode.TRIGGER_INVALID,
                        "触发器 " + trigger.getTriggerId() + " 存在同类型同值重复分支: "
                                + matchType + "=" + branch.getMatchValue()));
                continue;
            }
            validateActions(graph, trigger, branch, errors);
        }
    }

    private void validateActions(ProcessGraph graph, TriggerConfig trigger, TriggerConfig.TriggerBranch branch,
                                 List<GraphValidationError> errors) {
        if (branch.getActions() == null) {
            return;
        }
        Set<String> actionIds = new HashSet<>();
        for (ActionConfig action : branch.getActions()) {
            String locator = trigger.getTriggerId() + ":" + branch.getBranchId() + ":"
                    + action.getActionId();
            if (action.getActionId() == null || action.getActionId().isBlank()) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作缺少稳定 ID（触发器 " + trigger.getTriggerId() + "）"));
                continue;
            }
            if (!actionIds.add(action.getActionId())) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 ID 重复: " + action.getActionId()));
                continue;
            }
            String type = action.getType() == null ? "" : action.getType().toUpperCase();
            if (!ACTION_TYPES.contains(type)) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作类型无效: " + action.getType()));
                continue;
            }
            if (!"START_SINGLE".equals(type)
                    && (action.getSourceVariable() == null || action.getSourceVariable().isBlank())) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 " + action.getActionId() + ": 集合动作缺少 sourceVariable"));
                continue;
            }
            if ("START_GROUPED".equals(type)
                    && (action.getGroupBy() == null || action.getGroupBy().isBlank())) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 " + action.getActionId() + ": 分组动作缺少 groupBy 稳定分组身份"));
                continue;
            }
            if (action.getMaxDispatch() != null
                    && (action.getMaxDispatch() < 1 || action.getMaxDispatch() > 200)) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 " + action.getActionId() + ": maxDispatch 必须在 1—200 内"));
                continue;
            }
            validateActionTarget(action, locator, errors);
            validateActionMapping(action, locator, errors);
            if (action.isChild()) {
                validateChildAction(graph, action, locator, errors);
            }
        }
    }

    // ==================== CHILD 子流程动作（P64 阶段Ⅱ A05/A06） ====================

    private static final Set<String> WAIT_POLICIES = Set.of("ALL", "ANY", "COUNT", "NONE");

    /** CHILD 配置校验：等待策略/K 值/输出回写结构与父表单目标字段存在性。 */
    private void validateChildAction(ProcessGraph graph, ActionConfig action, String locator,
                                     List<GraphValidationError> errors) {
        String policy = action.getWaitPolicy() == null ? "ALL"
                : action.getWaitPolicy().toUpperCase();
        if (!WAIT_POLICIES.contains(policy)) {
            errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                    "子流程动作 " + action.getActionId() + " 等待策略无效: " + action.getWaitPolicy()
                            + "（ALL/ANY/COUNT/NONE）"));
            return;
        }
        if ("COUNT".equals(policy)) {
            if (action.getWaitCount() == null || action.getWaitCount() < 1) {
                errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                        "子流程动作 " + action.getActionId() + ": COUNT 策略必须配置正整数 K"));
                return;
            }
            if (action.getMaxDispatch() != null && action.getWaitCount() > action.getMaxDispatch()) {
                errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                        "子流程动作 " + action.getActionId() + ": K=" + action.getWaitCount()
                                + " 超过单次派发上限 " + action.getMaxDispatch()));
                return;
            }
        }
        ActionConfig.WriteBackConfig wb = action.getWriteBack();
        if (wb == null) {
            return;
        }
        if (wb.getResultNodeKey() == null || wb.getResultNodeKey().isBlank()) {
            errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                    "子流程动作 " + action.getActionId() + ": 回写配置缺少 resultNodeKey"));
            return;
        }
        boolean hasRowWrite = wb.getTableField() != null && !wb.getTableField().isBlank();
        if (hasRowWrite) {
            if (wb.getRowKeyField() == null || wb.getRowKeyField().isBlank()
                    || wb.getParentTableField() == null || wb.getParentTableField().isBlank()) {
                errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                        "子流程动作 " + action.getActionId() + ": 行级回写必须配置 rowKeyField 与 parentTableField"));
                return;
            }
            if (wb.getFields() == null || wb.getFields().isEmpty()) {
                errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                        "子流程动作 " + action.getActionId() + ": 行级回写必须配置允许列映射 fields"));
                return;
            }
        }
        if ((wb.getFields() == null || wb.getFields().isEmpty())
                && (wb.getMainFields() == null || wb.getMainFields().isEmpty())) {
            errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                    "子流程动作 " + action.getActionId() + ": 回写配置缺少任何字段映射"));
            return;
        }
        // 父表单目标字段存在性（主字段/来源表格列）
        Optional<String> parentDef = formDefinitionService.getFormDefinition(graph.getFormKey());
        if (parentDef.isEmpty()) {
            errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                    "子流程动作 " + action.getActionId() + ": 父业务表单定义不可用 " + graph.getFormKey()));
            return;
        }
        List<Map<String, Object>> parentFields = parseFields(parentDef.get());
        Map<String, Object> parentTableField = hasRowWrite
                ? findFieldDef(parentFields, wb.getParentTableField()) : null;
        if (hasRowWrite && parentTableField == null) {
            errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                    "子流程动作 " + action.getActionId() + ": 父业务表单缺少来源表格字段 "
                            + wb.getParentTableField()));
            return;
        }
        if (hasRowWrite) {
            Set<String> parentColumns = new HashSet<>();
            Object subFields = parentTableField.get("subFields");
            if (subFields instanceof List<?> columns) {
                columns.stream().filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .forEach(column -> {
                            Object name = column.get("name");
                            if (name != null) {
                                parentColumns.add(String.valueOf(name));
                            }
                        });
            }
            for (ActionConfig.FieldMapping mapping : wb.getFields()) {
                if (mapping.getToField() == null || !parentColumns.contains(mapping.getToField())) {
                    errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                            "子流程动作 " + action.getActionId() + ": 来源表格缺少回写列 "
                                    + mapping.getToField()));
                }
            }
        }
        if (wb.getMainFields() != null) {
            for (ActionConfig.FieldMapping mapping : wb.getMainFields()) {
                if (mapping.getToField() == null || findFieldDef(parentFields, mapping.getToField()) == null) {
                    errors.add(error(locator, BpmErrorCode.CHILD_ACTION_INVALID,
                            "子流程动作 " + action.getActionId() + ": 父业务表单缺少回写字段 "
                                    + mapping.getToField()));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findFieldDef(List<Map<String, Object>> fields, String name) {
        return fields.stream()
                .filter(field -> name.equals(String.valueOf(field.get("name"))))
                .findFirst().orElse(null);
    }

    private void validateActionTarget(ActionConfig action, String locator,
                                      List<GraphValidationError> errors) {
        if (action.getTargetFormKey() == null || action.getTargetFormKey().isBlank()) {
            errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                    "动作 " + action.getActionId() + ": 缺少目标表单 targetFormKey"));
            return;
        }
        Optional<FormDefDTO> targetForm = formDefinitionService.getFormDef(action.getTargetFormKey());
        if (targetForm.isEmpty() || !"PUBLISHED".equals(targetForm.get().getStatus())) {
            errors.add(error(locator, BpmErrorCode.ACTION_TARGET_INVALID,
                    "动作 " + action.getActionId() + ": 目标表单不存在或未发布 " + action.getTargetFormKey()));
            return;
        }
        if (action.getTargetProcessDefKey() == null || action.getTargetProcessDefKey().isBlank()) {
            errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                    "动作 " + action.getActionId() + ": 缺少目标流程 targetProcessDefKey"));
            return;
        }
        List<BpmFormBinding> bindings = formBindingService.findActiveByFormKey(action.getTargetFormKey());
        if (bindings.isEmpty() || bindings.size() > 1) {
            errors.add(error(locator, BpmErrorCode.ACTION_TARGET_INVALID,
                    "动作 " + action.getActionId() + ": 目标表单必须有唯一启用流程绑定（现有 "
                            + bindings.size() + " 条）"));
            return;
        }
        if (!action.getTargetProcessDefKey().equals(bindings.get(0).getProcessDefKey())) {
            errors.add(error(locator, BpmErrorCode.ACTION_TARGET_INVALID,
                    "动作 " + action.getActionId() + ": targetProcessDefKey 与目标表单启用绑定不一致"));
        }
    }

    private void validateActionMapping(ActionConfig action, String locator,
                                       List<GraphValidationError> errors) {
        if (action.getMapping() == null || action.getMapping().isEmpty()) {
            return;
        }
        Set<String> targetFieldsSeen = new LinkedHashSet<>();
        Optional<String> definitionJson = formDefinitionService.getFormDefinition(action.getTargetFormKey());
        List<Map<String, Object>> targetFields = definitionJson.map(this::parseFields).orElse(List.of());
        for (ActionConfig.ActionMapping mapping : action.getMapping()) {
            if (mapping.getTargetField() == null || mapping.getTargetField().isBlank()) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 " + action.getActionId() + ": 映射缺少目标字段"));
                continue;
            }
            if (!targetFieldsSeen.add(mapping.getTargetField())) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 " + action.getActionId() + ": 目标字段重复映射 " + mapping.getTargetField()));
                continue;
            }
            boolean hasSource = (mapping.getSourceVarId() != null && !mapping.getSourceVarId().isBlank())
                    || (mapping.getItemField() != null && !mapping.getItemField().isBlank())
                    || mapping.getLiteral() != null;
            if (!hasSource) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 " + action.getActionId() + ": 映射 " + mapping.getTargetField()
                                + " 缺少来源（sourceVarId/itemField/literal 三选一）"));
            }
            if (targetFields.stream().noneMatch(field -> mapping.getTargetField()
                    .equals(String.valueOf(field.get("name"))))) {
                errors.add(error(locator, BpmErrorCode.ACTION_INVALID,
                        "动作 " + action.getActionId() + ": 目标表单缺少字段 " + mapping.getTargetField()));
            }
        }
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFields(String definitionJson) {
        try {
            Map<String, Object> definition = objectMapper.readValue(definitionJson,
                    new TypeReference<Map<String, Object>>() { });
            Object fields = definition.get("fields");
            return fields instanceof List<?> list
                    ? list.stream().filter(Map.class::isInstance)
                            .map(item -> (Map<String, Object>) item).toList()
                    : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> findField(String definitionJson, String fieldName, String unused) {
        return parseFields(definitionJson).stream()
                .filter(field -> fieldName.equals(String.valueOf(field.get("name"))))
                .findFirst().orElse(null);
    }

    private boolean fieldFlag(Map<String, Object> field, String flag) {
        return Boolean.TRUE.equals(field.get(flag));
    }

    private GraphValidationError error(String elementId, BpmErrorCode code, String message) {
        return GraphValidationError.builder()
                .elementId(elementId)
                .errorCode(code.getCode())
                .message(message)
                .build();
    }
}
