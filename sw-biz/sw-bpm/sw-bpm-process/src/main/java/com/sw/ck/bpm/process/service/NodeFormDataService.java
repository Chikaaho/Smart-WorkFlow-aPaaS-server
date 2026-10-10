package com.sw.ck.bpm.process.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessDefVersion;
import com.sw.ck.bpm.process.entity.BpmTaskFormData;
import com.sw.ck.bpm.process.mapper.BpmTaskFormDataMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P64 任务级节点业务表单数据服务（ADR-P64-001 §1，A01）。
 * <p>
 * 绑定从冻结图（实例 def_version 优先，回退当前定义图）节点 {@code config.nodeForm.formKey} 解析；
 * 草稿与最终提交分行内状态（DRAFT→SUBMITTED）；提交校验按发布 definition 执行（必填/类型/
 * 字典值域/USER/DEPT 对象存在性），数据本体存 JSON，不走动态宽表、不触发流程发起链。
 * </p>
 */
@Slf4j
@Service
public class NodeFormDataService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    private final BpmTaskFormDataMapper taskFormDataMapper;
    private final BpmProcessDefService bpmProcessDefService;
    private final com.sw.ck.bpm.process.mapper.BpmProcessDefVersionMapper versionMapper;
    private final FormDefinitionService formDefinitionService;
    private final com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService;
    private final ObjectProvider<UserQueryFacade> userQueryFacadeProvider;
    private final ObjectProvider<DeptQueryFacade> deptQueryFacadeProvider;
    private final ObjectProvider<com.sw.ck.system.api.dict.DictFacade> dictFacadeProvider;
    private final ObjectMapper objectMapper;

    public NodeFormDataService(BpmTaskFormDataMapper taskFormDataMapper,
                               BpmProcessDefService bpmProcessDefService,
                               com.sw.ck.bpm.process.mapper.BpmProcessDefVersionMapper versionMapper,
                               FormDefinitionService formDefinitionService,
                               com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService,
                               ObjectProvider<UserQueryFacade> userQueryFacadeProvider,
                               ObjectProvider<DeptQueryFacade> deptQueryFacadeProvider,
                               ObjectProvider<com.sw.ck.system.api.dict.DictFacade> dictFacadeProvider,
                               ObjectMapper objectMapper) {
        this.taskFormDataMapper = taskFormDataMapper;
        this.bpmProcessDefService = bpmProcessDefService;
        this.versionMapper = versionMapper;
        this.formDefinitionService = formDefinitionService;
        this.approvalActionService = approvalActionService;
        this.userQueryFacadeProvider = userQueryFacadeProvider;
        this.deptQueryFacadeProvider = deptQueryFacadeProvider;
        this.dictFacadeProvider = dictFacadeProvider;
        this.objectMapper = objectMapper;
    }

    // ==================== 绑定解析 ====================

    /** 节点业务表单绑定（从图节点 config.nodeForm 解析）。 */
    public record NodeFormBinding(String formKey, String formVersion, String formName) {
    }

    /**
     * 解析节点业务表单绑定（优先实例冻结版本图，回退当前定义图）。
     *
     * @return empty = 未绑定（合法：该节点无节点表单能力配置）
     */
    public Optional<NodeFormBinding> resolveBinding(String processDefKey, Integer defVersion, String nodeKey) {
        ProcessGraph graph = loadGraph(processDefKey, defVersion);
        if (graph == null || graph.getElements() == null) {
            return Optional.empty();
        }
        return graph.getElements().stream()
                .filter(element -> "node".equals(element.getKind()) && nodeKey.equals(element.getId()))
                .map(GraphElement::getConfig)
                .filter(java.util.Objects::nonNull)
                .map(config -> config.get("nodeForm"))
                .filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value)
                .findFirst()
                .flatMap(this::toBinding);
    }

    private Optional<NodeFormBinding> toBinding(Map<?, ?> nodeForm) {
        Object formKeyObj = nodeForm.get("formKey");
        if (formKeyObj == null || String.valueOf(formKeyObj).isBlank()) {
            return Optional.empty();
        }
        String formKey = String.valueOf(formKeyObj).trim();
        Optional<FormDefDTO> def = formDefinitionService.getFormDef(formKey);
        if (def.isEmpty()) {
            // 配置存在但表单已不可用：fail closed（2432），不静默按未绑定处理
            throw new BaseException(BpmErrorCode.NODE_FORM_NOT_BOUND.getCode(),
                    "节点绑定的业务表单不可用: " + formKey);
        }
        // 任务级绑定版本冻结（复审05 P1-04b）：版本以发布冻结图中记录的 formVersion 为准
        // （发布时点冻结，任务创建/首次草稿/表单再发布前后都不漂移）；
        // 冻结图无记录（历史图/未冻结）时回退当前已发布版本（旧无绑定兼容口径）。
        Object frozenVersionObj = nodeForm.get("formVersion");
        String frozenVersion = frozenVersionObj == null ? null : String.valueOf(frozenVersionObj).trim();
        String formVersion = frozenVersion == null || frozenVersion.isBlank()
                ? (def.get().getFormVersion() == null ? null : String.valueOf(def.get().getFormVersion()))
                : frozenVersion;
        return Optional.of(new NodeFormBinding(formKey, formVersion, def.get().getName()));
    }

    /** 加载冻结图：defVersion 可定位版本行时用冻结 graph_json，否则回退当前定义图。 */
    public ProcessGraph loadGraph(String processDefKey, Integer defVersion) {
        try {
            BpmProcessDef definition = bpmProcessDefService.findByProcessKey(processDefKey);
            if (definition == null) {
                return null;
            }
            if (defVersion != null) {
                BpmProcessDefVersion version = versionMapper.selectOne(
                        Wrappers.<BpmProcessDefVersion>lambdaQuery()
                                .eq(BpmProcessDefVersion::getDefId, definition.getId())
                                .eq(BpmProcessDefVersion::getGraphVersion, defVersion)
                                .last("limit 1"));
                if (version != null && version.getGraphJson() != null && !version.getGraphJson().isBlank()) {
                    return objectMapper.readValue(version.getGraphJson(), ProcessGraph.class);
                }
            }
            if (definition.getGraphJson() == null) {
                return null;
            }
            return objectMapper.readValue(definition.getGraphJson(), ProcessGraph.class);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(BpmErrorCode.TRIGGER_INVALID.getCode(), "流程图解析失败: " + e.getMessage());
        }
    }

    // ==================== 任务数据读写 ====================

    public Optional<BpmTaskFormData> findByTaskId(Long tenantId, String taskId) {
        return Optional.ofNullable(taskFormDataMapper.selectOne(Wrappers.<BpmTaskFormData>lambdaQuery()
                .eq(BpmTaskFormData::getTenantId, tenantId)
                .eq(BpmTaskFormData::getTaskId, taskId)
                .last("limit 1")));
    }

    public List<BpmTaskFormData> listByInstance(Long tenantId, String processInstanceId) {
        return taskFormDataMapper.selectList(Wrappers.<BpmTaskFormData>lambdaQuery()
                .eq(BpmTaskFormData::getTenantId, tenantId)
                .eq(BpmTaskFormData::getProcessInstanceId, processInstanceId)
                .orderByAsc(BpmTaskFormData::getRoundNo)
                .orderByAsc(BpmTaskFormData::getId));
    }

    /** 本轮（指定轮次）已最终提交的节点表单数据（变量读取唯一入口；仅 SUBMITTED）。 */
    public List<BpmTaskFormData> listSubmitted(Long tenantId, String processInstanceId,
                                               String nodeKey, Long roundNo) {
        return taskFormDataMapper.selectList(Wrappers.<BpmTaskFormData>lambdaQuery()
                .eq(BpmTaskFormData::getTenantId, tenantId)
                .eq(BpmTaskFormData::getProcessInstanceId, processInstanceId)
                .eq(BpmTaskFormData::getNodeKey, nodeKey)
                .eq(BpmTaskFormData::getRoundNo, roundNo)
                .eq(BpmTaskFormData::getStatus, STATUS_SUBMITTED)
                .orderByAsc(BpmTaskFormData::getId));
    }

    /**
     * 指定节点全部有效轮次的最终提交（P64 阶段Ⅱ回写读取口径；仅 SUBMITTED，
     * 按轮次与提交顺序升序——调用方取末位即最新权威结果，来源含轮次/任务可追溯）。
     */
    public List<BpmTaskFormData> listSubmittedByNode(Long tenantId, String processInstanceId,
                                                     String nodeKey) {
        return taskFormDataMapper.selectList(Wrappers.<BpmTaskFormData>lambdaQuery()
                .eq(BpmTaskFormData::getTenantId, tenantId)
                .eq(BpmTaskFormData::getProcessInstanceId, processInstanceId)
                .eq(BpmTaskFormData::getNodeKey, nodeKey)
                .eq(BpmTaskFormData::getStatus, STATUS_SUBMITTED)
                .orderByAsc(BpmTaskFormData::getRoundNo)
                .orderByAsc(BpmTaskFormData::getId));
    }

    /**
     * 保存草稿（DRAFT upsert；已 SUBMITTED 的任务拒绝改写 2434）。
     */
    @Transactional
    public Long saveDraft(String processInstanceId, String processDefKey, String nodeKey, String taskId,
                          String formKey, Long bindingFormVersion, Map<String, Object> data) {
        Long tenantId = currentTenantId();
        BpmTaskFormData existing = findByTaskId(tenantId, taskId).orElse(null);
        if (existing != null && STATUS_SUBMITTED.equals(existing.getStatus())) {
            throw new BaseException(BpmErrorCode.NODE_FORM_ALREADY_SUBMITTED);
        }
        String json = toJson(data);
        if (existing == null) {
            BpmTaskFormData row = new BpmTaskFormData();
            row.setProcessInstanceId(processInstanceId);
            row.setProcessDefKey(processDefKey);
            row.setNodeKey(nodeKey);
            row.setTaskId(taskId);
            row.setRoundNo(currentRound(processInstanceId));
            row.setFormKey(formKey);
            row.setFormVersion(boundFormVersion(null, bindingFormVersion, formKey));
            row.setStatus(STATUS_DRAFT);
            row.setDataText(json);
            try {
                taskFormDataMapper.insert(row);
                return row.getId();
            } catch (DuplicateKeyException e) {
                throw new BaseException(BpmErrorCode.NODE_FORM_ALREADY_SUBMITTED);
            }
        }
        existing.setDataText(json);
        // 绑定版本冻结：草稿保存不随表单再发布改写既有任务的版本
        existing.setFormVersion(boundFormVersion(existing, bindingFormVersion, formKey));
        taskFormDataMapper.updateById(existing);
        return existing.getId();
    }

    /**
     * 合法最终提交（任务完成动作同事务调用）：校验 → SUBMITTED（幂等：同任务重放返回原行）。
     *
     * @return 数据行 ID；data 为 null 且无既有行 = 未填写，不落行（合法：无节点表单数据）
     */
    @Transactional
    public Long submitFinal(String processInstanceId, String processDefKey, String nodeKey, String taskId,
                            String formKey, Long bindingFormVersion, Map<String, Object> data, Long actorId) {
        Long tenantId = currentTenantId();
        BpmTaskFormData existing = findByTaskId(tenantId, taskId).orElse(null);
        if (existing != null && STATUS_SUBMITTED.equals(existing.getStatus())) {
            // 命令重放恢复：原提交事实已存在，返回原行（不重复校验/改写）
            return existing.getId();
        }
        Map<String, Object> effective = data == null
                ? (existing == null ? Map.of() : parseData(existing.getDataText()))
                : data;
        if (data != null) {
            List<String> errors = validateNodeFormData(formKey, boundFormVersion(existing, bindingFormVersion, formKey), effective);
            if (!errors.isEmpty()) {
                throw new BaseException(BpmErrorCode.NODE_FORM_VALIDATION_FAILED.getCode(),
                        BpmErrorCode.NODE_FORM_VALIDATION_FAILED.getMessage() + ": " + String.join("; ", errors));
            }
        }
        effective = assignTableRowIds(formKey, effective);
        if (effective.isEmpty() && existing == null) {
            return null;
        }
        String json = toJson(effective);
        if (existing == null) {
            BpmTaskFormData row = new BpmTaskFormData();
            row.setProcessInstanceId(processInstanceId);
            row.setProcessDefKey(processDefKey);
            row.setNodeKey(nodeKey);
            row.setTaskId(taskId);
            row.setRoundNo(currentRound(processInstanceId));
            row.setFormKey(formKey);
            row.setFormVersion(boundFormVersion(null, bindingFormVersion, formKey));
            row.setStatus(STATUS_SUBMITTED);
            row.setDataText(json);
            row.setSubmittedBy(actorId);
            row.setSubmitTime(LocalDateTime.now());
            try {
                taskFormDataMapper.insert(row);
                return row.getId();
            } catch (DuplicateKeyException e) {
                // 并发同任务提交：另一请求已合法提交，本次零副作用回查原行
                return findByTaskId(tenantId, taskId)
                        .map(BpmTaskFormData::getId)
                        .orElseThrow(() -> e);
            }
        }
        existing.setStatus(STATUS_SUBMITTED);
        existing.setDataText(json);
        existing.setSubmittedBy(actorId);
        existing.setSubmitTime(LocalDateTime.now());
        // 绑定版本冻结：最终提交沿用任务绑定版本（草稿建立时点/当前发布版本），
        // 表单之后的再发布不改写本任务已绑定的字段与校验语义
        existing.setFormVersion(boundFormVersion(existing, bindingFormVersion, formKey));
        taskFormDataMapper.updateById(existing);
        return existing.getId();
    }

    // ==================== 校验 ====================

    /**
     * 读取任务节点表单绑定版本对应的 definition（任务级绑定快照，审查02 P1-04b）。
     * <p>
     * 任务数据行一经建立即冻结其 formVersion（发布时点的已发布版本）；该表单后续再发布
     * 不改写既有任务的字段与校验语义。语义（复审03 P1-04b 澄清，按真实分支区分）：
     * </p>
     * <ul>
     *   <li>formVersion 为空 = 无绑定版本的历史任务行：<b>明确</b>回退当前已发布定义（调用方按“未绑定”对待）；</li>
     *   <li>formVersion 非空 = 任务已绑定版本：只读该版本快照；快照缺失<b>不再静默回退最新定义</b>，
     *       返回 empty 由调用方给出可诊断拒绝（绑定版本快照缺失）。</li>
     * </ul>
     */
    public Optional<String> loadBoundDefinition(String formKey, Long formVersion) {
        if (formVersion == null) {
            return formDefinitionService.getFormDefinition(formKey);
        }
        return formDefinitionService.getFormDefinitionSnapshot(formKey, formVersion.intValue());
    }

    /**
     * 任务级绑定版本：既有行（草稿/已提交）冻结其版本；新行取绑定版本
     * （=发布冻结图记录的 formVersion，复审05 P1-04b：任务创建即绑定，首次草稿前后
     * 表单再发布都不漂移）；两者均缺省时回退当前已发布版本（旧无绑定兼容口径）。
     */
    private Long boundFormVersion(BpmTaskFormData existing, Long bindingFormVersion, String formKey) {
        if (existing != null && existing.getFormVersion() != null) {
            return existing.getFormVersion();
        }
        if (bindingFormVersion != null) {
            return bindingFormVersion;
        }
        return frozenFormVersion(formKey);
    }

    /** 解析绑定版本串（发布冻结图给出的数字/字符串；缺省或非法返回 null）。 */
    public static Long parseBindingVersion(String formVersion) {
        if (formVersion == null || formVersion.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(formVersion.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按发布 definition 校验节点表单数据（必填/类型/字典值域/USER/DEPT 存在性/TABLE 子行）。
     *
     * @return 错误列表；空 = 通过
     */
    public List<String> validateNodeFormData(String formKey, Map<String, Object> data) {
        return validateNodeFormData(formKey, null, data);
    }

    /**
     * 按任务绑定版本的 definition 校验（formVersion 为空取当前定义）。
     */
    public List<String> validateNodeFormData(String formKey, Long formVersion, Map<String, Object> data) {
        List<String> errors = new ArrayList<>();
        Optional<String> definitionJson = loadBoundDefinition(formKey, formVersion);
        if (definitionJson.isEmpty()) {
            // 绑定版本快照缺失：可诊断拒绝，不按最新定义静默校验（复审03 P1-04b）
            errors.add(formVersion == null
                    ? "表单定义不可用: " + formKey
                    : "表单绑定版本快照缺失: " + formKey + "@v" + formVersion + "（不按最新定义静默校验，请管理员修复快照）");
            return errors;
        }
        List<Map<String, Object>> fields;
        try {
            Map<String, Object> definition = objectMapper.readValue(definitionJson.get(),
                    new TypeReference<Map<String, Object>>() { });
            Object fieldsObj = definition.get("fields");
            fields = fieldsObj instanceof List<?> list
                    ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                    : List.of();
        } catch (Exception e) {
            errors.add("表单定义解析失败: " + e.getMessage());
            return errors;
        }
        Set<String> definedNames = fields.stream()
                .map(field -> String.valueOf(field.get("name"))).collect(java.util.stream.Collectors.toSet());
        for (String key : data.keySet()) {
            if (!definedNames.contains(key)) {
                errors.add("未知字段: " + key);
            }
        }
        for (Map<String, Object> field : fields) {
            String name = String.valueOf(field.get("name"));
            String type = field.get("type") == null ? "TEXT" : String.valueOf(field.get("type")).toUpperCase();
            boolean required = Boolean.TRUE.equals(field.get("required"));
            Object value = data.get(name);
            if (isEmptyValue(value)) {
                if (required) {
                    errors.add(name + ": 必填");
                }
                continue;
            }
            validateFieldValue(field, name, type, value, errors);
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void validateFieldValue(Map<String, Object> field, String name, String type,
                                    Object value, List<String> errors) {
        switch (type) {
            case "NUMBER" -> {
                try {
                    new java.math.BigDecimal(String.valueOf(
                            value instanceof List<?> list ? list.get(0) : value));
                } catch (Exception e) {
                    errors.add(name + ": 必须是数字");
                }
            }
            case "BOOL" -> {
                if (!(value instanceof Boolean) && !"true".equalsIgnoreCase(String.valueOf(value))
                        && !"false".equalsIgnoreCase(String.valueOf(value))) {
                    errors.add(name + ": 必须是布尔值");
                }
            }
            case "TABLE" -> {
                if (!(value instanceof List<?> rows)) {
                    errors.add(name + ": 表格必须是行数组");
                    return;
                }
                List<Map<String, Object>> subFields = field.get("subFields") instanceof List<?> list
                        ? list.stream().filter(Map.class::isInstance)
                                .map(item -> (Map<String, Object>) item).toList()
                        : List.of();
                int index = 0;
                for (Object rowObj : rows) {
                    index++;
                    if (!(rowObj instanceof Map<?, ?>)) {
                        errors.add(name + ": 第" + index + "行不是对象");
                        continue;
                    }
                    Map<String, Object> row = (Map<String, Object>) rowObj;
                    for (Map<String, Object> sub : subFields) {
                        String subName = String.valueOf(sub.get("name"));
                        boolean subRequired = Boolean.TRUE.equals(sub.get("required"));
                        if (isEmptyValue(row.get(subName)) && subRequired) {
                            errors.add(name + "." + subName + ": 第" + index + "行必填");
                        }
                    }
                }
            }
            case "USER", "DEPT" -> {
                List<String> ids = value instanceof List<?> list
                        ? list.stream().map(item -> String.valueOf(item)).toList()
                        : List.of(String.valueOf(value));
                if (field.get("multiple") != null && Boolean.TRUE.equals(field.get("multiple"))
                        && !(value instanceof List<?>)) {
                    errors.add(name + ": 多选字段必须是 ID 数组");
                    return;
                }
                List<Long> parsed = new ArrayList<>();
                for (String id : ids) {
                    try {
                        parsed.add(Long.parseLong(id));
                    } catch (Exception e) {
                        errors.add(name + ": 必须是数字 ID");
                        return;
                    }
                }
                if (parsed.isEmpty()) {
                    return;
                }
                if ("USER".equals(type)) {
                    UserQueryFacade facade = userQueryFacadeProvider.getIfAvailable();
                    if (facade != null) {
                        List<Long> active = facade.findActiveUserIds(parsed, currentTenantId()).orElse(List.of());
                        if (active.size() < distinct(parsed).size()) {
                            errors.add(name + ": 含不存在或已停用的用户");
                        }
                    }
                } else {
                    DeptQueryFacade facade = deptQueryFacadeProvider.getIfAvailable();
                    if (facade != null) {
                        List<Long> active = facade.findActiveDeptIds(parsed).orElse(List.of());
                        if (active.size() < distinct(parsed).size()) {
                            errors.add(name + ": 含不存在或已停用的部门");
                        }
                    }
                }
            }
            case "DICT" -> {
                com.sw.ck.system.api.dict.DictFacade dictFacade = dictFacadeProvider.getIfAvailable();
                String dictType = field.get("dictType") == null ? null : String.valueOf(field.get("dictType"));
                if (dictFacade != null && dictType != null && !dictType.isBlank()) {
                    List<String> values = value instanceof List<?> list
                            ? list.stream().map(String::valueOf).toList()
                            : List.of(String.valueOf(value));
                    for (String code : values) {
                        if (!Boolean.TRUE.equals(dictFacade.isValidCode(dictType, code).orElse(false))) {
                            errors.add(name + ": 字典值超出值域: " + code);
                        }
                    }
                }
            }
            default -> {
                // TEXT/RICH_TEXT/DATE/TIME/REFERENCE/LABEL/MULTISELECT 等：字符串形状宽容处理
            }
        }
    }

    /** 加载发布 definition（解析为对象，供办理页渲染；不可用抛 2432）。 */
    public Map<String, Object> loadFormDefinition(String formKey) {
        try {
            Optional<String> json = formDefinitionService.getFormDefinition(formKey);
            if (json.isEmpty()) {
                throw new BaseException(BpmErrorCode.NODE_FORM_NOT_BOUND.getCode(),
                        "节点业务表单定义不可用: " + formKey);
            }
            return objectMapper.readValue(json.get(), new TypeReference<Map<String, Object>>() { });
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            throw new BaseException(BpmErrorCode.NODE_FORM_NOT_BOUND.getCode(),
                    "节点业务表单定义解析失败: " + formKey);
        }
    }

    // ==================== 轮次 ====================

    /** 当前轮次 = 实例 RETURN 动作数 + 1（与 TaskActionService#nextReturnRound 同源）。 */
    public long currentRound(String processInstanceId) {
        try {
            long returns = approvalActionService.findByProcessInstanceId(processInstanceId).stream()
                    .filter(item -> ApprovalAction.RETURN.name().equals(item.getAction()))
                    .count();
            return returns + 1;
        } catch (Exception e) {
            log.warn("轮次计算失败，按第 1 轮处理: processInstanceId={}, error={}", processInstanceId, e.getMessage());
            return 1;
        }
    }

    // ==================== 内部工具 ====================

    private Long frozenFormVersion(String formKey) {
        return formDefinitionService.getFormDef(formKey)
                .map(def -> def.getFormVersion() == null ? 1L : def.getFormVersion().longValue())
                .orElseThrow(() -> new BaseException(BpmErrorCode.NODE_FORM_NOT_BOUND.getCode(),
                        "节点绑定的业务表单不可用: " + formKey));
    }

    private Long currentTenantId() {
        var loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getTenantId() == null) {
            throw new BaseException(BpmErrorCode.NODE_FORM_NOT_BOUND.getCode(), "缺少租户上下文");
        }
        return loginUser.getTenantId();
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (Exception e) {
            throw new BaseException(BpmErrorCode.NODE_FORM_VALIDATION_FAILED.getCode(), "数据序列化失败");
        }
    }

    public Map<String, Object> parseData(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    /**
     * TABLE 字段行补稳定行 ID（无 id 的行分配 UUID；已有 id 保留）——
     * 变量集合拼接与后续按行回写依赖稳定行身份（主方向 §3.1）。
     */
    private Map<String, Object> assignTableRowIds(String formKey, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return data == null ? new LinkedHashMap<>() : data;
        }
        List<String> tableFields = tableFieldNames(formKey);
        if (tableFields.isEmpty()) {
            return data;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(data);
        for (String fieldName : tableFields) {
            Object value = enriched.get(fieldName);
            if (!(value instanceof List<?> rows)) {
                continue;
            }
            List<Map<String, Object>> enrichedRows = new ArrayList<>();
            for (Object rowObj : rows) {
                if (rowObj instanceof Map<?, ?> rowMap) {
                    Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) rowMap);
                    row.putIfAbsent("id", java.util.UUID.randomUUID().toString().replace("-", ""));
                    enrichedRows.add(row);
                }
            }
            enriched.put(fieldName, enrichedRows);
        }
        return enriched;
    }

    private List<String> tableFieldNames(String formKey) {
        try {
            Optional<String> definitionJson = formDefinitionService.getFormDefinition(formKey);
            if (definitionJson.isEmpty()) {
                return List.of();
            }
            Map<String, Object> definition = objectMapper.readValue(definitionJson.get(),
                    new TypeReference<Map<String, Object>>() { });
            Object fields = definition.get("fields");
            if (!(fields instanceof List<?> list)) {
                return List.of();
            }
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .filter(field -> "TABLE".equalsIgnoreCase(String.valueOf(field.get("type"))))
                    .map(field -> String.valueOf(field.get("name")))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Long> distinct(List<Long> ids) {
        return ids.stream().distinct().toList();
    }
}
