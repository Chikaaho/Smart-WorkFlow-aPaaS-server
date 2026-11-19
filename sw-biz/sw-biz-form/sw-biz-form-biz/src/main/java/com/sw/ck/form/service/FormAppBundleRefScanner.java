package com.sw.ck.form.service;

import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundlePreviewResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 预检外部引用扫描（与导入改写同一张引用位置清单）：遍历应用可见范围、表单/
 * 意见表单 definition（REFERENCE/DICT/DATASOURCE/字段权限/发起可见范围）、
 * 流程图（参与人/动态并行/触发器/结束通知）与 IoT 设备动作，产出
 * {@link FormBundlePreviewResult.ExternalRefPreview} 清单；另提供包内
 * 表单/流程 key 集合工具。
 */
@Service
class FormAppBundleRefScanner {

    @Autowired
    private FormBundleSupport support;

    void collectAllExternalRefs(FormAppBundleDocument document, TargetFacts facts,
                                Set<String> packageFormKeys, Set<String> packageProcessKeys,
                                List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        // 应用可见范围
        List<FormAppBundleDocument.ScopeBundle> scopes = document.getApp().getScopes();
        if (scopes != null) {
            for (FormAppBundleDocument.ScopeBundle scope : scopes) {
                String type = scope.getScopeType() == null ? "" : scope.getScopeType();
                String value = scope.getScopeId() == null ? "" : scope.getScopeId();
                switch (type) {
                    case FormAppScopeTypes.USER, FormAppScopeTypes.DEPT ->
                            sink.add(support.externalRef(type, value, "应用 可见范围", false, null));
                    case FormAppScopeTypes.ROLE -> sink.add(support.externalRef(FormAppScopeTypes.ROLE, value,
                            "应用 可见范围", Boolean.TRUE.equals(facts.roleCodes().get(value)), value));
                    case FormAppScopeTypes.POST -> sink.add(support.externalRef(FormAppScopeTypes.POST, value,
                            "应用 可见范围", Boolean.TRUE.equals(facts.postCodes().get(value)), value));
                    default -> {
                        // TENANT：租户自身，保留
                    }
                }
            }
        }
        // 表单定义
        Map<String, String> queryDatasourceNames = new LinkedHashMap<>();
        for (FormAppBundleDocument.FormBundle form : document.getForms()) {
            if (form.getExtQueries() != null) {
                for (FormAppBundleDocument.ExtQueryBundle q : form.getExtQueries()) {
                    if (q.getQueryKey() != null) {
                        queryDatasourceNames.putIfAbsent(q.getQueryKey(), q.getDatasourceName());
                    }
                }
            }
        }
        for (FormAppBundleDocument.FormBundle form : document.getForms()) {
            String formPath = "表单 " + form.getName();
            collectVisibilityScopeRefs(form.getVisibilityScope(), formPath, sink);
            collectDefinitionRefs(support.parseObjectOrEmpty(form.getConfigDefinition()), formPath,
                    packageFormKeys, queryDatasourceNames, facts, sink);
            if (form.getExtQueries() != null) {
                for (FormAppBundleDocument.ExtQueryBundle q : form.getExtQueries()) {
                    if (q.getDatasourceName() != null && !q.getDatasourceName().isBlank()) {
                        boolean matched = support.findDatasourceIdByName(q.getDatasourceName()).isPresent();
                        sink.add(support.externalRef("DATASOURCE", q.getDatasourceName(),
                                formPath + " · 扩展查询 " + q.getQueryKey(), matched,
                                matched ? q.getDatasourceName() : null));
                    }
                }
            }
        }
        // 意见表单定义
        for (FormAppBundleDocument.OpinionFormBundle opinion : document.getOpinionForms()) {
            collectDefinitionRefs(support.parseObjectOrEmpty(opinion.getConfigDefinition()),
                    "意见表单 " + opinion.getName(), packageFormKeys, queryDatasourceNames, facts, sink);
        }
        // 流程
        for (FormAppBundleDocument.ProcessBundle process : document.getProcesses()) {
            String processPath = "流程 " + process.getName();
            if (process.getCategoryName() != null && !process.getCategoryName().isBlank()) {
                boolean matched = support.findCategoryIdByName(process.getCategoryName()).isPresent();
                sink.add(support.externalRef("CATEGORY", process.getCategoryName(),
                        processPath + " · 分类", matched, matched ? process.getCategoryName() : null));
            }
            collectGraphRefs(process, packageFormKeys, packageProcessKeys, facts, sink);
            if (process.getIotDeviceActionJson() != null && !process.getIotDeviceActionJson().isBlank()) {
                Map<String, Object> iot = support.parseObjectOrNull(process.getIotDeviceActionJson());
                if (iot != null && "FIXED".equalsIgnoreCase(support.text(iot.get("deviceSource")))
                        && iot.get("deviceId") != null) {
                    sink.add(support.externalRef("IOT_DEVICE", String.valueOf(iot.get("deviceId")),
                            processPath + " · IoT 设备", false, null));
                }
            }
        }
    }

    private void collectVisibilityScopeRefs(String visibilityScope, String formPath,
                                            List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        if (visibilityScope == null || visibilityScope.isBlank()) {
            return;
        }
        Map<String, Object> scope = support.parseObjectOrNull(visibilityScope);
        if (scope != null && scope.get("userIds") instanceof List<?> ids && !ids.isEmpty()) {
            sink.add(support.externalRef(FormAppScopeTypes.USER, support.joinValues(ids),
                    formPath + " · 发起可见范围", false, null));
        }
    }

    private void collectDefinitionRefs(Map<String, Object> definition, String formPath,
                                       Set<String> packageFormKeys,
                                       Map<String, String> queryDatasourceNames, TargetFacts facts,
                                       List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        if (definition == null) {
            return;
        }
        Object fields = definition.get("fields");
        if (fields instanceof List<?> fieldList) {
            for (Object fieldObj : fieldList) {
                collectFieldRefs(fieldObj, formPath, packageFormKeys, queryDatasourceNames, facts, sink);
            }
        }
        if (definition.get("fieldPermissions") instanceof Map<?, ?> perms) {
            for (Map.Entry<?, ?> entry : perms.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> modes)) {
                    continue;
                }
                for (Map.Entry<?, ?> mode : modes.entrySet()) {
                    if (!(mode.getValue() instanceof List<?> subjects)) {
                        continue;
                    }
                    for (Object subjectObj : subjects) {
                        String subject = support.text(subjectObj);
                        if (subject == null) {
                            continue;
                        }
                        String fieldPath = formPath + " · 字段权限 " + entry.getKey();
                        if (subject.startsWith("user:")) {
                            sink.add(support.externalRef(FormAppScopeTypes.USER, subject, fieldPath, false, null));
                        } else if (subject.startsWith("dept:")) {
                            sink.add(support.externalRef(FormAppScopeTypes.DEPT, subject, fieldPath, false, null));
                        } else if (subject.startsWith("role:")) {
                            String code = subject.substring("role:".length());
                            sink.add(support.externalRef(FormAppScopeTypes.ROLE, subject, fieldPath,
                                    Boolean.TRUE.equals(facts.roleCodes().get(code)),
                                    Boolean.TRUE.equals(facts.roleCodes().get(code)) ? subject : null));
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectFieldRefs(Object fieldObj, String formPath, Set<String> packageFormKeys,
                                  Map<String, String> queryDatasourceNames, TargetFacts facts,
                                  List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        String type = support.text(field.get("type"));
        String fieldLabel = support.text(field.get("label"));
        String fieldPath = formPath + (fieldLabel == null ? "" : " · 字段 " + fieldLabel);
        if ("REFERENCE".equalsIgnoreCase(type)) {
            String targetKey = support.text(field.get("targetFormId"));
            if (targetKey != null && !packageFormKeys.contains(targetKey)) {
                sink.add(support.externalRef("FORM", targetKey, fieldPath + " · 引用", false, null));
            }
        } else if ("DICT".equalsIgnoreCase(type)) {
            String dictCode = support.text(field.get("dictType"));
            if (dictCode != null) {
                String action = facts.dictActions().get(dictCode);
                sink.add(support.externalRef("DICT", dictCode, fieldPath + " · 字典",
                        !"OCCUPIED".equals(action),
                        "OCCUPIED".equals(action) ? null : action));
            }
        } else if ("DATASOURCE".equalsIgnoreCase(type)) {
            if (field.get("dsBinding") instanceof Map<?, ?> binding) {
                String queryKey = support.text(binding.get("queryKey"));
                if (queryKey != null) {
                    // 契约行随包导入自愈；可绑定与否取决于数据源能否按名称匹配
                    String datasourceName = queryDatasourceNames.get(queryKey);
                    boolean datasourceMatched = datasourceName == null || datasourceName.isBlank()
                            || support.findDatasourceIdByName(datasourceName).isPresent();
                    sink.add(support.externalRef("QUERY", queryKey, fieldPath + " · 数据源绑定",
                            datasourceMatched, datasourceMatched ? null : datasourceName));
                }
            }
        }
        if (field.get("subFields") instanceof List<?> subList) {
            for (Object sub : subList) {
                collectFieldRefs(sub, formPath, packageFormKeys, queryDatasourceNames, facts, sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectGraphRefs(FormAppBundleDocument.ProcessBundle process, Set<String> packageFormKeys,
                                  Set<String> packageProcessKeys, TargetFacts facts,
                                  List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        Map<String, Object> graph = support.parseObjectOrNull(process.getGraphJson());
        if (graph == null) {
            return;
        }
        String processPath = "流程 " + process.getName();
        if (graph.get("elements") instanceof List<?> elements) {
            for (Object elementObj : elements) {
                if (!(elementObj instanceof Map<?, ?> element)
                        || !"node".equals(support.text(element.get("kind")))
                        || !(element.get("config") instanceof Map<?, ?> configRaw)) {
                    continue;
                }
                Map<String, Object> config = (Map<String, Object>) configRaw;
                String nodeName = support.text(config.get("name"));
                String nodePath = processPath + (nodeName == null ? "" : " · 节点 " + nodeName);
                collectParticipantRef(config.get("participant"), facts, sink, nodePath);
                collectParticipantRef(config.get("approver"), facts, sink, nodePath);
                if ("DYNAMIC_PARALLEL".equalsIgnoreCase(support.text(element.get("type")))
                        && config.get("source") instanceof Map<?, ?> sourceRaw
                        && "FIXED".equalsIgnoreCase(support.text(((Map<?, ?>) sourceRaw).get("type")))) {
                    sink.add(support.externalRef(
                            FormAppScopeTypes.DEPT.equalsIgnoreCase(support.text(((Map<?, ?>) sourceRaw).get("objectType")))
                                    ? FormAppScopeTypes.DEPT : FormAppScopeTypes.USER,
                            support.valueText(((Map<?, ?>) sourceRaw).get("value")),
                            nodePath + " · 动态并行来源", false, null));
                }
            }
        }
        if (graph.get("triggers") instanceof List<?> triggers) {
            for (Object triggerObj : triggers) {
                if (!(triggerObj instanceof Map<?, ?> trigger)
                        || !(trigger.get("branches") instanceof List<?> branches)) {
                    continue;
                }
                for (Object branchObj : branches) {
                    if (!(branchObj instanceof Map<?, ?> branch)
                            || !(branch.get("actions") instanceof List<?> actions)) {
                        continue;
                    }
                    for (Object actionObj : actions) {
                        if (!(actionObj instanceof Map<?, ?> action)) {
                            continue;
                        }
                        String targetProcessKey = support.text(action.get("targetProcessDefKey"));
                        if (targetProcessKey != null && !packageProcessKeys.contains(targetProcessKey)) {
                            sink.add(support.externalRef("PROCESS", targetProcessKey,
                                    processPath + " · 触发器动作", false, null));
                        }
                        String targetFormKey = support.text(action.get("targetFormKey"));
                        if (targetFormKey != null && !packageFormKeys.contains(targetFormKey)) {
                            sink.add(support.externalRef("FORM", targetFormKey,
                                    processPath + " · 触发器动作", false, null));
                        }
                    }
                }
            }
        }
        if (graph.get("notification") instanceof Map<?, ?> notification
                && notification.get("otherRecipientIds") instanceof List<?> ids && !ids.isEmpty()) {
            sink.add(support.externalRef(FormAppScopeTypes.USER, support.joinValues(ids),
                    processPath + " · 结束通知接收人", false, null));
        }
    }

    @SuppressWarnings("unchecked")
    private void collectParticipantRef(Object participantObj, TargetFacts facts,
                                       List<FormBundlePreviewResult.ExternalRefPreview> sink, String nodePath) {
        if (!(participantObj instanceof Map<?, ?> participantRaw)) {
            return;
        }
        Map<String, Object> participant = (Map<String, Object>) participantRaw;
        String strategy = support.text(participant.get("strategy"));
        if (strategy == null) {
            strategy = support.text(participant.get("approverType"));
        }
        if (strategy == null) {
            return;
        }
        switch (strategy.toUpperCase()) {
            case "FIXED_USER" -> sink.add(support.externalRef("FIXED_USER", support.valueText(participant.get("value")),
                    nodePath + " · 参与人（指定用户）", false, null));
            case "DEPT_LEADER" -> sink.add(support.externalRef(FormAppScopeTypes.DEPT,
                    support.valueText(participant.get("value")),
                    nodePath + " · 参与人（部门负责人）", false, null));
            case FormAppScopeTypes.ROLE -> collectCodeListRef(participant.get("value"), facts.roleCodes(), sink,
                    nodePath + " · 参与人（角色）", FormAppScopeTypes.ROLE);
            case FormAppScopeTypes.POST -> collectCodeListRef(participant.get("value"), facts.postCodes(), sink,
                    nodePath + " · 参与人（岗位）", FormAppScopeTypes.POST);
            case "DEPT_POST" -> {
                if (participant.get("value") instanceof Map<?, ?> mapping) {
                    if (mapping.get("deptId") != null) {
                        sink.add(support.externalRef(FormAppScopeTypes.DEPT, String.valueOf(mapping.get("deptId")),
                                nodePath + " · 参与人（部门+岗位）", false, null));
                    }
                    String postCode = support.text(mapping.get("postCode"));
                    if (postCode != null) {
                        sink.add(support.externalRef(FormAppScopeTypes.POST, postCode, nodePath + " · 参与人（部门+岗位）",
                                Boolean.TRUE.equals(facts.postCodes().get(postCode)),
                                Boolean.TRUE.equals(facts.postCodes().get(postCode)) ? postCode : null));
                    }
                }
            }
            default -> {
                // 图内/表达式/适配器引用，不外发
            }
        }
    }

    private void collectCodeListRef(Object value, Map<String, Boolean> codeFacts,
                                    List<FormBundlePreviewResult.ExternalRefPreview> sink,
                                    String location, String kind) {
        if (value instanceof List<?> codes) {
            for (Object codeObj : codes) {
                String code = support.text(codeObj);
                if (code == null) {
                    continue;
                }
                boolean matched = Boolean.TRUE.equals(codeFacts.get(code));
                sink.add(support.externalRef(kind, code, location, matched, matched ? code : null));
            }
        } else if (support.hasValue(value)) {
            String code = support.valueText(value);
            boolean matched = Boolean.TRUE.equals(codeFacts.get(code));
            sink.add(support.externalRef(kind, code, location, matched, matched ? code : null));
        }
    }

    Set<String> packageFormKeys(FormAppBundleDocument document) {
        Set<String> keys = new LinkedHashSet<>();
        document.getForms().forEach(f -> keys.add(f.getFormKey()));
        document.getOpinionForms().forEach(f -> keys.add(f.getFormKey()));
        return keys;
    }

    Set<String> packageProcessKeys(FormAppBundleDocument document) {
        Set<String> keys = new LinkedHashSet<>();
        document.getProcesses().forEach(p -> keys.add(p.getProcessKey()));
        return keys;
    }
}
