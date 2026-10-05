package com.sw.ck.bpm.engine.participant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 表单字段参与人策略（P63）：从实例表单数据解析人员/部门稳定对象 ID。
 * <p>
 * value 形状（{@link ParticipantStrategy#formFieldConfigError} 单一权威）：
 * {@code {objectType: USER|DEPT, scope: MAIN|TABLE, field, tableField, column}}。
 * USER=所选有效人员作为参与人；DEPT=服务端按权威组织关系解析各部门唯一负责人
 * （{@code findDeptLeaderMap}），不信任客户端给出的负责人。
 * 表单数据经 {@link FormRecordReadFacade} 显式租户读取（多选已解码为 ID 列表，
 * 表格行含稳定行 id）；对象失效/缺负责人按明确错误拒绝，不静默跳过。
 * </p>
 */
@Component
public class FormFieldParticipantResolver implements NodeParticipantResolver {

    private final FormRecordReadFacade formRecordReadFacade;
    private final UserQueryFacade userQueryFacade;
    private final ObjectMapper objectMapper;

    public FormFieldParticipantResolver(FormRecordReadFacade formRecordReadFacade,
                                        UserQueryFacade userQueryFacade,
                                        ObjectMapper objectMapper) {
        this.formRecordReadFacade = formRecordReadFacade;
        this.userQueryFacade = userQueryFacade;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.FORM_FIELD);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        Map<String, Object> binding = bindingOf(context.getStrategyValue());
        boolean user = "USER".equalsIgnoreCase(text(binding.get("objectType")));
        boolean tableScope = "TABLE".equalsIgnoreCase(text(binding.get("scope")));
        Long tenantId = context.getTenantId();
        String formKey = context.getFormKey();
        String recordId = context.getBusinessKey();
        if (tenantId == null || formKey == null || formKey.isBlank()
                || recordId == null || recordId.isBlank()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID.getCode(),
                    "FORM_FIELD 解析缺少租户/表单/记录上下文");
        }

        FormRecordReadFacade.FormRecordData record = formRecordReadFacade
                .findRecord(tenantId, formKey, recordId)
                .orElseThrow(() -> new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY.getCode(),
                        "实例表单记录不存在或已删除，无法解析参与人"));

        // 来源对象 ID（保持来源顺序，去重）
        Set<String> objectIds = new LinkedHashSet<>();
        if (tableScope) {
            List<Map<String, Object>> rows = record.tables()
                    .getOrDefault(text(binding.get("tableField")), List.of());
            String column = text(binding.get("column"));
            for (Map<String, Object> row : rows) {
                collectIds(row.get(column), objectIds);
            }
        } else {
            collectIds(record.fields().get(text(binding.get("field"))), objectIds);
        }
        if (objectIds.isEmpty()) {
            return Optional.of(List.of());
        }

        if (user) {
            List<Long> parsed = parseLongs(objectIds);
            Optional<List<Long>> active = userQueryFacade.findActiveUserIds(parsed, tenantId);
            if (active.isEmpty()) {
                return Optional.of(List.of());
            }
            Set<Long> valid = new LinkedHashSet<>(active.orElseThrow());
            if (valid.size() != parsed.stream().distinct().count()) {
                throw new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY.getCode(),
                        "表单来源包含失效或越权人员，已拒绝解析参与人");
            }
            return Optional.of(parsed.stream().map(String::valueOf).toList());
        }

        // DEPT：服务端权威解析各部门唯一负责人（同租户 + 部门有效 + 负责人启用）
        List<Long> deptIds = parseLongs(objectIds);
        Optional<Map<Long, Long>> leaderMap = userQueryFacade.findDeptLeaderMap(deptIds, tenantId);
        if (leaderMap.isEmpty()) {
            return Optional.of(List.of());
        }
        Map<Long, Long> leaders = leaderMap.orElseThrow();
        if (leaders.size() != deptIds.stream().distinct().count()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY.getCode(),
                    "表单来源包含失效部门或缺失负责人的部门，已拒绝解析参与人");
        }
        return Optional.of(deptIds.stream()
                .map(deptId -> String.valueOf(leaders.get(deptId)))
                .distinct()
                .toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bindingOf(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            return (Map<String, Object>) mapping;
        }
        if (value instanceof String json && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID);
            }
        }
        throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID);
    }

    /** 单值/列表/JSON 数组串统一收集为去重对象 ID（来源顺序）。 */
    private void collectIds(Object value, Set<String> sink) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addId(item, sink);
            }
            return;
        }
        if (value instanceof String text && text.trim().startsWith("[")) {
            try {
                List<Object> decoded = objectMapper.readValue(text,
                        new TypeReference<List<Object>>() { });
                decoded.forEach(item -> addId(item, sink));
                return;
            } catch (Exception ignored) {
                // 非法 JSON 串按普通单值处理
            }
        }
        addId(value, sink);
    }

    private void addId(Object item, Set<String> sink) {
        if (item == null) {
            return;
        }
        String text = String.valueOf(item).trim();
        if (text.matches("\\d+") && Long.parseLong(text) > 0) {
            sink.add(text);
        }
    }

    private List<Long> parseLongs(Set<String> ids) {
        List<Long> parsed = new ArrayList<>();
        for (String id : ids) {
            parsed.add(Long.valueOf(id));
        }
        return parsed;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
