package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批历史富化：意见字段展示视图 + 处理人部门名。
 * <p>
 * 「我发起的」实例详情与任务详情（{@code GET /workflow/tasks/{taskId}}）共用本实现，
 * 避免两套解析口径漂移：意见字段按冻结表单快照逐项给出中文标签与展示值；
 * 部门按处理人主部门解析，查不到或部门已停用时保持空（调用方显示占位符）。
 * </p>
 */
public final class ApprovalHistoryEnricher {

    private static final Logger log = LoggerFactory.getLogger(ApprovalHistoryEnricher.class);

    private ApprovalHistoryEnricher() {
    }

    /** 意见字段展示视图 + 处理人部门一次完成（任务详情与实例详情共用入口）。 */
    public static void enrich(List<ApprovalHistoryItemDTO> history,
                              Map<String, ApprovalActionRecord> actionByTask,
                              ObjectMapper objectMapper,
                              TaskActionService taskActionService,
                              UserQueryFacade userQueryFacade,
                              DeptQueryFacade deptQueryFacade) {
        enrichOpinionFields(history, actionByTask, objectMapper, taskActionService);
        enrichAssigneeDept(history, userQueryFacade, deptQueryFacade);
    }

    /**
     * 意见字段展示视图：按提交时冻结的意见表单配置逐项给出中文标签与展示值。
     * <p>
     * 人员显示姓名（解析不到保留 ID）、日期按本地格式、多选拼接入文本；
     * 旧动作记录缺快照或未填意见时保持空列表，不虚构字段。
     * </p>
     */
    public static void enrichOpinionFields(List<ApprovalHistoryItemDTO> history,
                                           Map<String, ApprovalActionRecord> actionByTask,
                                           ObjectMapper objectMapper,
                                           TaskActionService taskActionService) {
        if (objectMapper == null || history == null || history.isEmpty()) {
            return;
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (ApprovalHistoryItemDTO item : history) {
            ApprovalActionRecord action = item.getTaskId() == null ? null : actionByTask.get(item.getTaskId());
            if (action == null || action.getOpinionFormSnapshot() == null
                    || action.getOpinionFormSnapshot().isBlank()) {
                continue;
            }
            List<Map<String, Object>> definitions = opinionFieldDefinitions(action.getOpinionFormSnapshot(), objectMapper);
            if (definitions.isEmpty() || item.getOpinionData() == null) {
                continue;
            }
            List<ApprovalHistoryItemDTO.OpinionFieldView> views = new ArrayList<>();
            for (Map<String, Object> definition : definitions) {
                String type = definition.get("type") == null ? "" : String.valueOf(definition.get("type"));
                if ("NOTE".equalsIgnoreCase(type)) {
                    continue;
                }
                String key = definition.get("key") == null ? null : String.valueOf(definition.get("key"));
                if (key == null || key.isBlank()) {
                    continue;
                }
                ApprovalHistoryItemDTO.OpinionFieldView view = new ApprovalHistoryItemDTO.OpinionFieldView();
                view.setName(key);
                view.setLabel(definition.get("label") == null ? key : String.valueOf(definition.get("label")));
                view.setType(type);
                Object value = item.getOpinionData().get(key);
                view.setValue(value);
                if ("USER".equalsIgnoreCase(type) && value != null) {
                    for (Object raw : (value instanceof List<?> list ? list : List.of(value))) {
                        String text = raw == null ? "" : String.valueOf(raw).trim();
                        if (text.matches("\\d+")) {
                            userIds.add(Long.valueOf(text));
                        }
                    }
                }
                views.add(view);
            }
            item.setOpinionFields(views);
        }
        Map<Long, String> userNames = userIds.isEmpty() || taskActionService == null
                ? Map.of() : taskActionService.resolveUserNames(userIds);
        for (ApprovalHistoryItemDTO item : history) {
            if (item.getOpinionFields() == null) {
                continue;
            }
            for (ApprovalHistoryItemDTO.OpinionFieldView view : item.getOpinionFields()) {
                view.setDisplayValue(opinionDisplayValue(view.getType(), view.getValue(), userNames));
            }
        }
    }

    /**
     * 处理人部门名：按主部门解析（部门停用或处理人无部门时保持空，由调用方显示占位符）。
     * 解析失败不阻断历史响应，保留空值并在日志留痕。
     */
    public static void enrichAssigneeDept(List<ApprovalHistoryItemDTO> history,
                                          UserQueryFacade userQueryFacade,
                                          DeptQueryFacade deptQueryFacade) {
        if (history == null || history.isEmpty() || userQueryFacade == null || deptQueryFacade == null) {
            return;
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (ApprovalHistoryItemDTO item : history) {
            if (item.getAssignee() != null && item.getAssignee().matches("\\d+")) {
                userIds.add(Long.valueOf(item.getAssignee()));
            }
        }
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, Long> userDeptIds = userQueryFacade.getUserDeptIds(userIds).orElse(Map.of());
        if (userDeptIds.isEmpty()) {
            return;
        }
        Set<Long> deptIds = new LinkedHashSet<>(userDeptIds.values());
        Map<Long, String> deptNames = deptQueryFacade.findActiveDeptNames(deptIds).orElse(Map.of());
        for (ApprovalHistoryItemDTO item : history) {
            if (item.getAssignee() == null || !item.getAssignee().matches("\\d+")) {
                continue;
            }
            Long deptId = userDeptIds.get(Long.valueOf(item.getAssignee()));
            if (deptId != null) {
                item.setAssigneeDept(deptNames.get(deptId));
            }
        }
    }

    /** 冻结快照 JSON → 字段定义列表；结构不可解析时返回空列表（不阻断历史响应）。 */
    public static List<Map<String, Object>> opinionFieldDefinitions(String snapshotJson, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            JsonNode fields = root == null ? null : root.path("opinionForm").path("fields");
            if (fields == null || !fields.isArray() || fields.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode node : fields) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                Map<String, Object> field = new LinkedHashMap<>();
                for (String name : List.of("key", "type", "label", "maxLength")) {
                    if (node.hasNonNull(name)) {
                        field.put(name, node.get(name).isNumber() ? node.get(name).asInt() : node.get(name).asText());
                    }
                }
                out.add(field);
            }
            return out;
        } catch (Exception e) {
            log.warn("审批意见快照解析失败，忽略字段展示视图: {}", e.getMessage());
            return List.of();
        }
    }

    /** 意见字段展示文本：人员姓名、日期本地格式（yyyy-MM-dd HH:mm）、多选拼接。 */
    public static String opinionDisplayValue(String type, Object value, Map<Long, String> userNames) {
        if (value == null) {
            return "";
        }
        String normalized = type == null ? "" : type.toUpperCase();
        if ("USER".equals(normalized)) {
            List<String> ids = new ArrayList<>();
            for (Object raw : (value instanceof List<?> list ? list : List.of(value))) {
                String text = raw == null ? "" : String.valueOf(raw).trim();
                if (!text.isEmpty()) {
                    ids.add(text.matches("\\d+")
                            ? userNames.getOrDefault(Long.valueOf(text), text) : text);
                }
            }
            return String.join("、", ids);
        }
        if ("CHECKBOX".equals(normalized) || "MULTISELECT".equals(normalized) || value instanceof List<?>) {
            List<String> items = new ArrayList<>();
            for (Object raw : (value instanceof List<?> list ? list : List.of(value))) {
                if (raw != null && !String.valueOf(raw).isBlank()) {
                    items.add(String.valueOf(raw));
                }
            }
            return String.join("、", items);
        }
        String text = String.valueOf(value);
        if (normalized.startsWith("DATE")) {
            String trimmed = text.replace('T', ' ');
            return trimmed.length() >= 16 ? trimmed.substring(0, 16) : trimmed;
        }
        return text;
    }
}
