package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;
import com.sw.ck.system.api.dept.DeptDirectoryRow;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BF20261013-021：脚本宿主函数上下文构建（组织目录 + 实例变量快照）。
 * <p>
 * 隔离 worker 无 DB 访问，组织与实例变量由宿主侧一次性解析后随执行请求下发。
 * 仅当脚本引用了相应函数（名字或英文别名做子串扫描）才做查询——既有脚本零额外开销；
 * 实例变量值按“可 JSON 序列化”过滤（不可序列化值跳过并告警，不影响判断主链路）。
 * </p>
 */
@Slf4j
@Service
public class BpmScriptFunctionContextFactory {

    /** 组织函数触发词（名字与英文别名）。 */
    private static final List<String> ORG_TOKENS = List.of(
            "获取部门负责人", "deptLeader", "获取分管领导", "deptSupervisor", "获取上级部门", "parentDept");
    /** 流程变量函数触发词（读或写都需要实例变量快照：写入回读与跨脚本传递都落在实例变量层）。 */
    private static final List<String> VARIABLE_TOKENS = List.of(
            "流程变量取值", "获取流程变量", "getVariable", "设置流程变量", "setVariable");

    private final DeptQueryFacade deptQueryFacade;
    private final BpmTaskFacade bpmTaskFacade;
    private final ObjectMapper objectMapper;

    public BpmScriptFunctionContextFactory(DeptQueryFacade deptQueryFacade,
                                           BpmTaskFacade bpmTaskFacade,
                                           ObjectMapper objectMapper) {
        this.deptQueryFacade = deptQueryFacade;
        this.bpmTaskFacade = bpmTaskFacade;
        this.objectMapper = objectMapper;
    }

    /**
     * 按脚本引用构建上下文；未引用组织/变量函数时返回 {@link ScriptFunctionContext#EMPTY}（零查询）。
     *
     * @param script            原始脚本（含 # 语法糖，扫描在原文上进行）
     * @param processInstanceId 流程实例 ID（实例变量快照源）
     * @param tenantId          租户 ID（组织目录源）
     */
    public ScriptFunctionContext build(String script, String processInstanceId, Long tenantId) {
        if (script == null || script.isBlank()) {
            return ScriptFunctionContext.EMPTY;
        }
        boolean orgNeeded = containsAny(script, ORG_TOKENS);
        boolean varsNeeded = containsAny(script, VARIABLE_TOKENS);
        if (!orgNeeded && !varsNeeded) {
            return ScriptFunctionContext.EMPTY;
        }
        Map<Long, Long> parentMap = Map.of();
        Map<Long, Long> leaderMap = Map.of();
        if (orgNeeded && tenantId != null) {
            List<DeptDirectoryRow> directory = deptQueryFacade.listDeptDirectory(tenantId).orElse(List.of());
            if (!directory.isEmpty()) {
                Map<Long, Long> parents = new LinkedHashMap<>();
                Map<Long, Long> leaders = new LinkedHashMap<>();
                for (DeptDirectoryRow row : directory) {
                    if (row.parentId() != null) {
                        parents.put(row.deptId(), row.parentId());
                    }
                    if (row.leaderId() != null) {
                        leaders.put(row.deptId(), row.leaderId());
                    }
                }
                parentMap = parents;
                leaderMap = leaders;
            }
        }
        Map<String, Object> instanceVariables = Map.of();
        if (varsNeeded && processInstanceId != null) {
            instanceVariables = bpmTaskFacade.getVariables(processInstanceId)
                    .map(this::jsonSafe)
                    .orElse(Map.of());
        }
        return new ScriptFunctionContext(parentMap, leaderMap, instanceVariables);
    }

    private static boolean containsAny(String script, List<String> tokens) {
        for (String token : tokens) {
            if (script.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 只保留可 JSON 序列化的实例变量值（不可序列化值跳过并告警）。 */
    private Map<String, Object> jsonSafe(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            try {
                objectMapper.writeValueAsString(entry.getValue());
                out.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("脚本实例变量快照跳过不可序列化值: {}（{}）", entry.getKey(), e.getMessage());
            }
        }
        return out;
    }
}
