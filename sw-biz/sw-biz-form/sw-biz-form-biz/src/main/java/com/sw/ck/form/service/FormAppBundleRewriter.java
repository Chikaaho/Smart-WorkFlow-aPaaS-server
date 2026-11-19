package com.sw.ck.form.service;

import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundleImportReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 导入时的引用改写（方向 §风险方向 引用位置清单逐项处理）：表单 definition
 * （REFERENCE/DICT/DATASOURCE/字段权限/发起可见范围）、流程图（processKey 归一、
 * nodeForm/opinionForm、参与人、TXN_ACTION、动态并行来源、触发器、结束通知）
 * 与 iotDeviceActionJson。改写结果直接修改传入的 Map/实体，匹配不上的外部
 * 引用置空并经 {@link ImportContext} 记入报告（D022-13）。
 */
@Service
class FormAppBundleRewriter {

    @Autowired
    private FormBundleSupport support;

    void rewriteDefinition(Map<String, Object> definition, ImportContext ctx, String formName) {
        if (definition == null) {
            return;
        }
        Object fields = definition.get("fields");
        if (fields instanceof List<?> fieldList) {
            for (Object fieldObj : fieldList) {
                rewriteField(fieldObj, ctx, formName);
            }
        }
        rewriteFieldPermissions(definition.get("fieldPermissions"), ctx, formName);
    }

    @SuppressWarnings("unchecked")
    private void rewriteField(Object fieldObj, ImportContext ctx, String formName) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        String type = support.text(field.get("type"));
        String fieldLabel = support.text(field.get("label"));
        String fieldPath = "表单 " + formName + (fieldLabel == null ? "" : " · 字段 " + fieldLabel);
        if ("REFERENCE".equalsIgnoreCase(type)) {
            Object target = field.get("targetFormId");
            String targetKey = support.text(target);
            if (targetKey != null) {
                if (ctx.formKeyMap.containsKey(targetKey)) {
                    field.put("targetFormId", ctx.formKeyMap.get(targetKey));
                } else if (ctx.opinionKeyMap.containsKey(targetKey)) {
                    field.put("targetFormId", ctx.opinionKeyMap.get(targetKey));
                } else {
                    // 包外表单引用：按外部引用处理，置空并记入报告（方向 §非目标）
                    field.put("targetFormId", null);
                    ctx.report.getClearedRefs().add(support.cleared("FORM", targetKey, fieldPath + " · 引用"));
                }
            }
        } else if ("DICT".equalsIgnoreCase(type)) {
            // dictType 为编码引用：目标租户复用/新建则保留；被占用则永不可解析，
            // 按 D022-13（匹配不上的置空）清空字段绑定并记字段位置，方便发布前补配
            String dictCode = support.text(field.get("dictType"));
            if (dictCode != null && "OCCUPIED".equals(ctx.facts.dictActions().get(dictCode))) {
                field.put("dictType", null);
                ctx.report.getClearedRefs().add(support.cleared("DICT", dictCode, fieldPath + " · 字典"));
            }
        } else if ("DATASOURCE".equalsIgnoreCase(type)) {
            Object binding = field.get("dsBinding");
            if (binding instanceof Map<?, ?> bindingRaw) {
                Map<String, Object> dsBinding = (Map<String, Object>) bindingRaw;
                String oldKey = support.text(dsBinding.get("queryKey"));
                if (oldKey != null) {
                    String finalKey = ctx.queryKeyMap.get(oldKey);
                    Long datasourceId = ctx.datasourceIdByOldQueryKey.get(oldKey);
                    if (finalKey != null && datasourceId != null) {
                        dsBinding.put("queryKey", finalKey);
                    } else {
                        // 数据源未匹配：契约行无法落库（datasource_id NOT NULL），绑定整体置空
                        dsBinding.put("queryKey", null);
                        dsBinding.put("version", null);
                        ctx.report.getClearedRefs().add(support.cleared("DATASOURCE",
                                ctx.datasourceNameByOldQueryKey.getOrDefault(oldKey, oldKey),
                                fieldPath + " · 数据源绑定"));
                    }
                }
            }
        }
        Object subFields = field.get("subFields");
        if (subFields instanceof List<?> subList) {
            for (Object sub : subList) {
                rewriteField(sub, ctx, formName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteFieldPermissions(Object permsObj, ImportContext ctx, String formName) {
        if (!(permsObj instanceof Map<?, ?> permsRaw)) {
            return;
        }
        Map<String, Object> perms = (Map<String, Object>) permsRaw;
        for (Map.Entry<String, Object> entry : perms.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> modesRaw)) {
                continue;
            }
            Map<String, Object> modes = (Map<String, Object>) modesRaw;
            for (Map.Entry<String, Object> modeEntry : modes.entrySet()) {
                if (!(modeEntry.getValue() instanceof List<?> subjects)) {
                    continue;
                }
                List<Object> kept = new ArrayList<>();
                for (Object subjectObj : subjects) {
                    String subject = support.text(subjectObj);
                    if (subject == null) {
                        continue;
                    }
                    if (subject.startsWith("user:")) {
                        ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.USER, subject,
                                "表单 " + formName + " · 字段权限 " + entry.getKey()));
                        continue;
                    }
                    if (subject.startsWith("dept:")) {
                        ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.DEPT, subject,
                                "表单 " + formName + " · 字段权限 " + entry.getKey()));
                        continue;
                    }
                    if (subject.startsWith("role:")) {
                        String code = subject.substring("role:".length());
                        if (Boolean.TRUE.equals(ctx.facts.roleCodes().get(code))) {
                            ctx.report.getMatchedRoles().add(support.reused("role:" + code, "角色 " + code, null));
                        } else {
                            ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.ROLE, subject,
                                    "表单 " + formName + " · 字段权限 " + entry.getKey()));
                            continue;
                        }
                    }
                    kept.add(subject);
                }
                modes.put(modeEntry.getKey(), kept);
            }
        }
    }

    /** 发起可见范围：userIds 指定用户置空（空 = 本租户全部用户），返回改写后的 JSON 或 null。 */
    String clearedVisibilityScope(String visibilityScope, String formName, ImportContext ctx) {
        if (visibilityScope == null || visibilityScope.isBlank()) {
            return null;
        }
        Map<String, Object> scope = support.parseObjectOrNull(visibilityScope);
        if (scope == null) {
            return null;
        }
        Object userIds = scope.get("userIds");
        if (userIds instanceof List<?> ids && !ids.isEmpty()) {
            ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.USER, support.joinValues(ids),
                    "表单 " + formName + " · 发起可见范围"));
        }
        return null;
    }

    /**
     * 流程图改写（方向 §风险方向 引用位置清单逐项处理）：
     * <ul>
     *   <li>processKey 归一为新 key；</li>
     *   <li>nodeForm.formKey → 意见表单新 key，formVersion → 新表单当前版本；</li>
     *   <li>opinionForm.formId → 新意见表单 id，version → 新表单当前版本；</li>
     *   <li>participant/approver 按 D022-13 匹配或置空并记报告；</li>
     *   <li>TXN_ACTION actionId → 包内交易动作新 id；</li>
     *   <li>触发器动作 targetProcessDefKey / targetFormKey 包内改写、包外置空记报告；</li>
     *   <li>notification.otherRecipientIds、DYNAMIC_PARALLEL FIXED 来源置空记报告；</li>
     *   <li>节点/边 id、变量、通知渠道枚举、FORM_FIELD 等图内引用原样保留。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    void rewriteProcessGraph(Map<String, Object> graph, FormAppBundleDocument.ProcessBundle process,
                             ImportContext ctx) {
        graph.put("processKey", ctx.processKeyMap.get(process.getProcessKey()));
        Object elements = graph.get("elements");
        if (elements instanceof List<?> elementList) {
            for (Object elementObj : elementList) {
                if (!(elementObj instanceof Map<?, ?> elementRaw)) {
                    continue;
                }
                Map<String, Object> element = (Map<String, Object>) elementRaw;
                if (!"node".equals(support.text(element.get("kind")))) {
                    continue;
                }
                Object configObj = element.get("config");
                if (!(configObj instanceof Map<?, ?> configRaw)) {
                    continue;
                }
                Map<String, Object> config = (Map<String, Object>) configRaw;
                String nodeName = support.text(config.get("name"));
                String nodePath = "流程 " + process.getName()
                        + (nodeName == null ? "" : " · 节点 " + nodeName);
                rewriteNodeForm(config.get("nodeForm"), ctx);
                rewriteOpinionForm(config.get("opinionForm"), ctx, nodePath);
                rewriteParticipant(config.get("participant"), ctx, nodePath);
                rewriteParticipant(config.get("approver"), ctx, nodePath);
                if ("TXN_ACTION".equalsIgnoreCase(support.text(element.get("type")))) {
                    String actionId = support.text(config.get("actionId"));
                    if (actionId != null) {
                        String newActionId = ctx.txnActionIdMap.get(actionId);
                        if (newActionId != null) {
                            config.put("actionId", newActionId);
                        } else {
                            config.put("actionId", null);
                            ctx.report.getClearedRefs().add(support.cleared("TXN_ACTION", actionId,
                                    nodePath + " · 交易动作"));
                        }
                    }
                }
                if ("DYNAMIC_PARALLEL".equalsIgnoreCase(support.text(element.get("type")))) {
                    rewriteDynamicParallelSource(config.get("source"), ctx, nodePath);
                }
            }
        }
        rewriteTriggers(graph.get("triggers"), ctx, process.getName());
        rewriteProcessNotification(graph.get("notification"), ctx, process.getName());
    }

    @SuppressWarnings("unchecked")
    private void rewriteNodeForm(Object nodeFormObj, ImportContext ctx) {
        if (!(nodeFormObj instanceof Map<?, ?> nodeFormRaw)) {
            return;
        }
        Map<String, Object> nodeForm = (Map<String, Object>) nodeFormRaw;
        String formKey = support.text(nodeForm.get("formKey"));
        if (formKey != null) {
            String finalKey = ctx.opinionKeyMap.containsKey(formKey)
                    ? ctx.opinionKeyMap.get(formKey) : ctx.formKeyMap.get(formKey);
            if (finalKey != null) {
                nodeForm.put("formKey", finalKey);
                nodeForm.put("formVersion", 1);
            } else {
                nodeForm.put("formKey", null);
                nodeForm.put("formVersion", null);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteOpinionForm(Object opinionObj, ImportContext ctx, String nodePath) {
        if (!(opinionObj instanceof Map<?, ?> opinionRaw)) {
            return;
        }
        Map<String, Object> opinionForm = (Map<String, Object>) opinionRaw;
        String oldFormId = support.text(opinionForm.get("formId"));
        if (oldFormId != null) {
            String newFormId = ctx.opinionIdMap.get(oldFormId);
            if (newFormId != null) {
                opinionForm.put("formId", newFormId);
                opinionForm.put("version", 1);
            } else {
                opinionForm.put("formId", null);
                opinionForm.put("version", null);
            }
        }
    }

    /** 参与人策略改写（D022-13）：FIXED_USER/DEPT 置空；ROLE/POST 编码匹配；DEPT_POST 部门置空。 */
    @SuppressWarnings("unchecked")
    private void rewriteParticipant(Object participantObj, ImportContext ctx, String nodePath) {
        if (!(participantObj instanceof Map<?, ?> participantRaw)) {
            return;
        }
        Map<String, Object> participant = (Map<String, Object>) participantRaw;
        String strategy = support.text(participant.get("strategy"));
        if (strategy == null) {
            // 兼容旧 approver 形态 {approverType, value}
            strategy = support.text(participant.get("approverType"));
        }
        if (strategy == null) {
            return;
        }
        switch (strategy.toUpperCase()) {
            case "FIXED_USER" -> {
                Object value = participant.get("value");
                if (support.hasValue(value)) {
                    ctx.report.getClearedRefs().add(support.cleared("FIXED_USER", support.valueText(value),
                            nodePath + " · 参与人（指定用户）"));
                    participant.put("value", null);
                }
            }
            case "DEPT_LEADER" -> {
                Object value = participant.get("value");
                if (support.hasValue(value)) {
                    ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.DEPT, support.valueText(value),
                            nodePath + " · 参与人（部门负责人）"));
                    participant.put("value", null);
                }
            }
            case FormAppScopeTypes.ROLE -> rewriteCodeList(participant, ctx.facts.roleCodes(), ctx,
                    nodePath + " · 参与人（角色）", ctx.report.getMatchedRoles());
            case FormAppScopeTypes.POST -> rewriteCodeList(participant, ctx.facts.postCodes(), ctx,
                    nodePath + " · 参与人（岗位）", ctx.report.getMatchedPosts());
            case "DEPT_POST" -> {
                Object value = participant.get("value");
                if (value instanceof Map<?, ?> mappingRaw) {
                    Map<String, Object> mapping = (Map<String, Object>) mappingRaw;
                    Object deptId = mapping.get("deptId");
                    if (deptId != null) {
                        ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.DEPT,
                                String.valueOf(deptId), nodePath + " · 参与人（部门+岗位）"));
                    }
                    mapping.put("deptId", null);
                    String postCode = support.text(mapping.get("postCode"));
                    if (postCode != null && !Boolean.TRUE.equals(ctx.facts.postCodes().get(postCode))) {
                        ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.POST, postCode,
                                nodePath + " · 参与人（部门+岗位）"));
                        mapping.put("postCode", null);
                    }
                }
            }
            default -> {
                // FORM_FIELD / NODE_FORM_AGGREGATE / EXPRESSION / ADAPTER：图内或表达式引用，原样保留
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteCodeList(Map<String, Object> participant, Map<String, Boolean> codeFacts,
                                 ImportContext ctx, String location, List<FormBundleImportReport.ReusedObject> matchedSink) {
        Object value = participant.get("value");
        if (!(value instanceof List<?> codes)) {
            if (support.hasValue(value)) {
                String code = support.valueText(value);
                if (Boolean.TRUE.equals(codeFacts.get(code))) {
                    matchedSink.add(support.reused(code, code, null));
                } else {
                    ctx.report.getClearedRefs().add(support.cleared(
                            codeFacts == ctx.facts.roleCodes() ? FormAppScopeTypes.ROLE : FormAppScopeTypes.POST,
                            code, location));
                    participant.put("value", null);
                }
            }
            return;
        }
        List<Object> kept = new ArrayList<>();
        boolean isRole = codeFacts == ctx.facts.roleCodes();
        for (Object codeObj : codes) {
            String code = support.text(codeObj);
            if (code == null) {
                continue;
            }
            if (Boolean.TRUE.equals(codeFacts.get(code))) {
                kept.add(codeObj);
                matchedSink.add(support.reused(code, code, null));
            } else {
                ctx.report.getClearedRefs().add(support.cleared(
                        isRole ? FormAppScopeTypes.ROLE : FormAppScopeTypes.POST, code, location));
            }
        }
        participant.put("value", kept.isEmpty() ? null : kept);
    }

    @SuppressWarnings("unchecked")
    private void rewriteDynamicParallelSource(Object sourceObj, ImportContext ctx, String nodePath) {
        if (!(sourceObj instanceof Map<?, ?> sourceRaw)) {
            return;
        }
        Map<String, Object> source = (Map<String, Object>) sourceRaw;
        if (!"FIXED".equalsIgnoreCase(support.text(source.get("type")))) {
            return;
        }
        String objectType = support.text(source.get("objectType"));
        Object value = source.get("value");
        if (support.hasValue(value)) {
            String kind = FormAppScopeTypes.DEPT.equalsIgnoreCase(objectType) ? FormAppScopeTypes.DEPT : FormAppScopeTypes.USER;
            ctx.report.getClearedRefs().add(support.cleared(kind, support.valueText(value),
                    nodePath + " · 动态并行来源"));
            source.put("value", null);
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteTriggers(Object triggersObj, ImportContext ctx, String processName) {
        if (!(triggersObj instanceof List<?> triggers)) {
            return;
        }
        for (Object triggerObj : triggers) {
            if (!(triggerObj instanceof Map<?, ?> triggerRaw)) {
                continue;
            }
            Map<String, Object> trigger = (Map<String, Object>) triggerRaw;
            Object branches = trigger.get("branches");
            if (!(branches instanceof List<?> branchList)) {
                continue;
            }
            for (Object branchObj : branchList) {
                if (!(branchObj instanceof Map<?, ?> branchRaw)) {
                    continue;
                }
                Map<String, Object> branch = (Map<String, Object>) branchRaw;
                Object actions = branch.get("actions");
                if (!(actions instanceof List<?> actionList)) {
                    continue;
                }
                for (Object actionObj : actionList) {
                    if (!(actionObj instanceof Map<?, ?> actionRaw)) {
                        continue;
                    }
                    Map<String, Object> action = (Map<String, Object>) actionRaw;
                    String targetProcessKey = support.text(action.get("targetProcessDefKey"));
                    if (targetProcessKey != null) {
                        String newKey = ctx.processKeyMap.get(targetProcessKey);
                        if (newKey != null) {
                            action.put("targetProcessDefKey", newKey);
                        } else {
                            action.put("targetProcessDefKey", null);
                            ctx.report.getClearedRefs().add(support.cleared("PROCESS", targetProcessKey,
                                    "流程 " + processName + " · 触发器动作"));
                        }
                    }
                    String targetFormKey = support.text(action.get("targetFormKey"));
                    if (targetFormKey != null) {
                        String newKey = ctx.formKeyMap.get(targetFormKey);
                        if (newKey != null) {
                            action.put("targetFormKey", newKey);
                        } else {
                            action.put("targetFormKey", null);
                            ctx.report.getClearedRefs().add(support.cleared("FORM", targetFormKey,
                                    "流程 " + processName + " · 触发器动作"));
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteProcessNotification(Object notificationObj, ImportContext ctx, String processName) {
        if (!(notificationObj instanceof Map<?, ?> notificationRaw)) {
            return;
        }
        Map<String, Object> notification = (Map<String, Object>) notificationRaw;
        Object otherIds = notification.get("otherRecipientIds");
        if (otherIds instanceof List<?> ids && !ids.isEmpty()) {
            ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.USER, support.joinValues(ids),
                    "流程 " + processName + " · 结束通知接收人"));
            notification.put("otherRecipientIds", null);
        }
    }

    /** iotDeviceActionJson：deviceSource=FIXED 时 deviceId 置空并记报告（设备按外部引用置空）。 */
    String rewriteIotDeviceAction(String raw, String processName, ImportContext ctx) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Map<String, Object> config = support.parseObjectOrNull(raw);
        if (config == null) {
            return raw;
        }
        if ("FIXED".equalsIgnoreCase(support.text(config.get("deviceSource"))) && config.get("deviceId") != null) {
            ctx.report.getClearedRefs().add(support.cleared("IOT_DEVICE", String.valueOf(config.get("deviceId")),
                    "流程 " + processName + " · IoT 设备"));
            config.put("deviceId", null);
            return support.writeJson(config);
        }
        return raw;
    }
}
