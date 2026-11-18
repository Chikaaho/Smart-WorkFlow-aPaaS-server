package com.sw.ck.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点轨迹收尾与节点事件发射（自 AgentGraphInterpreter 拆出，行为不变）。
 * <p>
 * 完成一条节点轨迹（节点级耗时 + 该时点变量表快照）并按 G1-7 约定经
 * {@link AgentExecutionEventContext} 实时发射 NODE_STARTED / NODE_FINISHED 事件；
 * 大字段截断，避免事件表/轮询被长文本拖慢。
 * </p>
 */
final class AgentGraphTraceRecorder {

    /** 工具入参 JSON 编码（Jackson，替代旧 spring-ai JsonParser） */
    static final ObjectMapper JSON = new ObjectMapper();

    private AgentGraphTraceRecorder() {
    }

    /** 完成一条节点轨迹：节点级耗时（出队到本步结束）+ 该时点变量表快照 */
    static void finishTrace(AgentGraphInterpreter.NodeExecutionTrace trace, long stepStartNanos,
                            Map<String, String> variables) {
        trace.setNodeLatencyMs((System.nanoTime() - stepStartNanos) / 1_000_000);
        trace.setEndTime(LocalDateTime.now());
        trace.setVariableSnapshot(new HashMap<>(variables));
        // G1-7：节点结束事件（耗时/入参/出参摘要/失败原因，大字段截断）
        emitNodeEvent("NODE_FINISHED", trace);
    }

    /** G1-7 节点事件载荷：STARTED 只带身份，FINISHED 带状态、耗时与出入参摘要。 */
    static void emitNodeEvent(String type, AgentGraphInterpreter.NodeExecutionTrace trace) {
        if (AgentExecutionEventContext.get() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", trace.getNodeId());
        payload.put("nodeType", trace.getNodeType());
        payload.put("branchId", trace.getBranchId());
        if ("NODE_FINISHED".equals(type)) {
            payload.put("status", trace.getStatus());
            payload.put("latencyMs", trace.getNodeLatencyMs());
            payload.put("input", truncateForEvent(trace.getInputText(), 2000));
            payload.put("output", truncateForEvent(trace.getOutputText(), 2000));
            payload.put("errorMessage", truncateForEvent(trace.getErrorMessage(), 1000));
            if (trace.getChildExecutionId() != null) {
                payload.put("childExecutionId", trace.getChildExecutionId());
            }
        }
        try {
            AgentExecutionEventContext.emitNode(type, trace.getNodeId(), JSON.writeValueAsString(payload));
        } catch (JsonProcessingException ignored) {
            AgentExecutionEventContext.emitNode(type, trace.getNodeId(), null);
        }
    }

    /** 事件载荷大字段截断（避免事件表/轮询被长文本拖慢）。 */
    private static String truncateForEvent(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "…(truncated)";
    }
}
