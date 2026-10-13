package com.sw.ck.bpm.process.service;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTaskFormData;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P64 BPM 变量运行快照解析（ADR-P64-001 §2，A02）。
 * <p>
 * 一次触发评估构建一份一致快照（同事务读点）；类型精确不隐式转换；
 * USER/DEPT 集合按稳定 ID 并集去重；ROWS 拼接保留来源追踪字段；
 * 必填缺失返回 missingRequired（阻止触发）；快照序列化 ≤1MiB 超限判 tooLarge。
 * </p>
 */
@Slf4j
@Service
public class BpmVariableSnapshotService {

    public static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;

    private static final Set<String> SYSTEM_KEYS = Set.of(
            "processInstanceId", "processDefKey", "businessKey", "formKey",
            "initiatorId", "tenantId", "currentNodeKey", "roundNo");

    private final NodeFormDataService nodeFormDataService;
    private final FormRecordReadFacade formRecordReadFacade;
    private final ObjectMapper objectMapper;

    public BpmVariableSnapshotService(NodeFormDataService nodeFormDataService,
                                      FormRecordReadFacade formRecordReadFacade,
                                      ObjectMapper objectMapper) {
        this.nodeFormDataService = nodeFormDataService;
        this.formRecordReadFacade = formRecordReadFacade;
        this.objectMapper = objectMapper;
    }

    /** 快照构建结果。 */
    public record SnapshotResult(Map<String, Object> values, String json, boolean tooLarge,
                                 List<String> missingRequired, List<String> errors) {
        public boolean failed() {
            return tooLarge || (missingRequired != null && !missingRequired.isEmpty())
                    || (errors != null && !errors.isEmpty());
        }
    }

    /**
     * 构建授权变量快照。
     *
     * @param variableDefs    流程全部变量定义（冻结图）
     * @param authorizedVarIds 触发器授权读取的 varId（未列入的不进入快照）
     * @param currentNodeKey  触发事件所在节点
     * @param roundNo         当前轮次
     */
    public SnapshotResult buildSnapshot(Long tenantId, BpmInstance instance,
                                        List<ProcessVariableDef> variableDefs,
                                        List<String> authorizedVarIds,
                                        String currentNodeKey, Long roundNo) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (variableDefs == null || variableDefs.isEmpty() || authorizedVarIds == null) {
            return finish(values, missing, errors);
        }
        ResolutionContext context = new ResolutionContext(tenantId, instance, roundNo);
        for (String varId : authorizedVarIds) {
            ProcessVariableDef def = variableDefs.stream()
                    .filter(item -> varId.equals(item.getVarId()))
                    .findFirst().orElse(null);
            if (def == null) {
                errors.add("授权变量未定义: " + varId);
                continue;
            }
            try {
                Object value = resolveVariable(def, context, currentNodeKey, missing, errors);
                if (value == RESOLVE_SKIP) {
                    continue;
                }
                values.put(varId, value);
                if (value == null && !Boolean.TRUE.equals(def.getNullable())) {
                    missing.add(def.getVarId());
                }
            } catch (Exception e) {
                errors.add("变量取值失败 " + varId + ": " + e.getMessage());
            }
        }
        return finish(values, missing, errors);
    }

    private static final Object RESOLVE_SKIP = new Object();

    /** 一次评估内的懒加载缓存（同事务一致读点）。 */
    private class ResolutionContext {
        final Long tenantId;
        final BpmInstance instance;
        final Long roundNo;
        Map<String, Object> mainFields;
        Map<String, List<Map<String, Object>>> mainTables = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> nodeFormCache = new LinkedHashMap<>();

        ResolutionContext(Long tenantId, BpmInstance instance, Long roundNo) {
            this.tenantId = tenantId;
            this.instance = instance;
            this.roundNo = roundNo;
        }

        void ensureMainFormLoaded() {
            if (mainFields != null) {
                return;
            }
            FormRecordReadFacade.FormRecordData record = formRecordReadFacade
                    .findRecord(tenantId, instance.getFormKey(), instance.getBusinessKey())
                    .orElse(null);
            mainFields = record == null ? Map.of() : record.fields();
            mainTables.putAll(record == null ? Map.of() : record.tables());
        }

        List<Map<String, Object>> nodeFormRows(String nodeKey) {
            return nodeFormCache.computeIfAbsent(nodeKey,
                    key -> nodeFormDataService
                            .listSubmitted(tenantId, instance.getProcessInstanceId(), key,
                                    roundNo == null ? 1L : roundNo)
                            .stream()
                            .map(row -> Map.<String, Object>of(
                                    "taskId", row.getTaskId() == null ? "" : row.getTaskId(),
                                    "data", nodeFormDataService.parseData(row.getDataText())))
                            .toList());
        }
    }

    private Object resolveVariable(ProcessVariableDef def, ResolutionContext context,
                                   String currentNodeKey, List<String> missing, List<String> errors) {
        String source = def.getSource() == null ? "" : def.getSource().toUpperCase();
        String type = def.getType() == null ? "" : def.getType().toUpperCase();
        return switch (source) {
            case "MAIN_FORM" -> {
                context.ensureMainFormLoaded();
                yield resolveFromMainForm(def, type, context.mainFields, context.mainTables);
            }
            case "NODE_FORM" -> {
                if (def.getSourceNodeKey() == null || def.getSourceNodeKey().isBlank()) {
                    errors.add("变量 " + def.getVarId() + ": NODE_FORM 来源缺少 sourceNodeKey");
                    yield RESOLVE_SKIP;
                }
                yield resolveFromNodeForm(def, type, context.nodeFormRows(def.getSourceNodeKey()),
                        missing, errors);
            }
            case "SYSTEM" -> resolveSystem(def, context.instance, currentNodeKey, context.roundNo);
            default -> {
                errors.add("未知变量来源: " + def.getSource());
                yield RESOLVE_SKIP;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Object resolveFromMainForm(ProcessVariableDef def, String type,
                                       Map<String, Object> mainFields,
                                       Map<String, List<Map<String, Object>>> mainTables) {
        String field = def.getSourceField();
        if ("ROWS".equals(type)) {
            List<Map<String, Object>> rows = mainTables == null ? null : mainTables.get(field);
            return rows == null ? null : List.copyOf(rows);
        }
        Object raw = mainFields == null ? null : mainFields.get(field);
        return coerce(raw, type, def);
    }

    @SuppressWarnings("unchecked")
    private Object resolveFromNodeForm(ProcessVariableDef def, String type,
                                       List<Map<String, Object>> submittedRows,
                                       List<String> missing, List<String> errors) {
        String field = def.getSourceFormField();
        String aggregation = def.getAggregation() == null ? "NONE" : def.getAggregation().toUpperCase();
        if (submittedRows.isEmpty()) {
            return null;
        }
        switch (type) {
            case "USER_SET", "DEPT_SET" -> {
                if (!"UNION".equals(aggregation)) {
                    errors.add("变量 " + def.getVarId() + ": 集合类型必须配置 UNION 聚合");
                    return RESOLVE_SKIP;
                }
                Set<String> union = new LinkedHashSet<>();
                for (Map<String, Object> row : submittedRows) {
                    Map<String, Object> data = (Map<String, Object>) row.get("data");
                    collectIds(data == null ? null : data.get(field), union);
                }
                return new ArrayList<>(union);
            }
            case "ROWS" -> {
                List<Map<String, Object>> concat = new ArrayList<>();
                for (Map<String, Object> row : submittedRows) {
                    Map<String, Object> data = (Map<String, Object>) row.get("data");
                    Object tableValue = data == null ? null : data.get(field);
                    if (tableValue instanceof List<?> rows) {
                        for (Object rowObj : rows) {
                            if (rowObj instanceof Map<?, ?> rowMap) {
                                Map<String, Object> traced = new LinkedHashMap<>((Map<String, Object>) rowMap);
                                // 保留来源追踪：任务身份不覆盖行内已有键
                                traced.putIfAbsent("_sourceTaskId", row.get("taskId"));
                                concat.add(traced);
                            }
                        }
                    }
                }
                return concat;
            }
            default -> {
                // 标量：多任务同字段必须唯一取值（NONE 聚合），不得任取最后一条
                LinkedHashSet<Object> distinctValues = new LinkedHashSet<>();
                for (Map<String, Object> row : submittedRows) {
                    Map<String, Object> data = (Map<String, Object>) row.get("data");
                    Object raw = data == null ? null : data.get(field);
                    distinctValues.add(normalizeScalar(raw));
                }
                if (distinctValues.size() > 1 && !"UNION".equals(aggregation)) {
                    errors.add("变量 " + def.getVarId() + ": 多任务同字段存在多个不同取值，"
                            + "必须选定唯一任务或配置聚合规则");
                    return RESOLVE_SKIP;
                }
                Object only = distinctValues.iterator().next();
                return coerce(only, type, def);
            }
        }
    }

    private Object resolveSystem(ProcessVariableDef def, BpmInstance instance,
                                 String currentNodeKey, Long roundNo) {
        String field = def.getSourceField();
        return switch (field == null ? "" : field) {
            case "processInstanceId" -> instance.getProcessInstanceId();
            case "processDefKey" -> instance.getProcessDefKey();
            case "businessKey" -> instance.getBusinessKey();
            case "formKey" -> instance.getFormKey();
            case "initiatorId" -> instance.getInitiatorId() == null ? null : String.valueOf(instance.getInitiatorId());
            case "tenantId" -> instance.getTenantId() == null ? null : String.valueOf(instance.getTenantId());
            case "currentNodeKey" -> currentNodeKey;
            case "roundNo" -> roundNo;
            default -> {
                log.warn("系统变量白名单外字段拒绝: {}", field);
                yield null;
            }
        };
    }

    /** 类型精确取值：不做字符串/布尔/数字隐式转换；形状不符按可诊断错误处理（返回 null 由 missing 兜底）。 */
    @SuppressWarnings("unchecked")
    private Object coerce(Object raw, String type, ProcessVariableDef def) {
        if (raw == null) {
            return null;
        }
        switch (type) {
            case "NUMBER" -> {
                if (raw instanceof Number number) {
                    return number;
                }
                try {
                    return new java.math.BigDecimal(String.valueOf(raw));
                } catch (Exception e) {
                    return null;
                }
            }
            case "BOOLEAN" -> {
                return raw instanceof Boolean bool ? bool : null;
            }
            case "STRING" -> {
                return raw instanceof String text ? text : null;
            }
            case "USER", "DEPT" -> {
                List<String> ids = new ArrayList<>();
                collectIds(raw, ids);
                return ids.size() == 1 ? ids.get(0) : null;
            }
            case "USER_SET", "DEPT_SET" -> {
                Set<String> ids = new LinkedHashSet<>();
                collectIds(raw, ids);
                return new ArrayList<>(ids);
            }
            case "ROWS" -> {
                if (!(raw instanceof List<?> rows)) {
                    return null;
                }
                List<Map<String, Object>> mapped = rows.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
                return List.copyOf(mapped);
            }
            default -> {
                return raw instanceof String text ? text : null;
            }
        }
    }

    /** USER/DEPT 值形状统一收集（单值数字串 / 数组 / JSON 串）。 */
    private void collectIds(Object raw, java.util.Collection<String> sink) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    sink.add(String.valueOf(item));
                }
            }
            return;
        }
        String text = String.valueOf(raw).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<String> parsed = objectMapper.readValue(text,
                        new TypeReference<List<String>>() { });
                sink.addAll(parsed);
                return;
            } catch (Exception ignore) {
                // 非 JSON 数组按单值处理
            }
        }
        if (!text.isBlank()) {
            sink.add(text);
        }
    }

    private Object normalizeScalar(Object raw) {
        if (raw instanceof Number number) {
            return new java.math.BigDecimal(String.valueOf(number));
        }
        return raw;
    }

    private SnapshotResult finish(Map<String, Object> values, List<String> missing, List<String> errors) {
        String json;
        try {
            json = JSON.toJSONString(Map.of("variables", values));
        } catch (Exception e) {
            json = "{}";
            errors.add("快照序列化失败: " + e.getMessage());
        }
        boolean tooLarge = json != null
                && json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SNAPSHOT_BYTES;
        if (tooLarge) {
            json = null;
        }
        return new SnapshotResult(values, json, tooLarge, missing, errors);
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
