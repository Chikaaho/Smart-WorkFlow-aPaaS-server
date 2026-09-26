package com.sw.ck.bpm.process.nodefunc;

import com.sw.ck.bpm.api.nodefunc.NodeFunctionContext;
import com.sw.ck.bpm.api.nodefunc.NodeFunctionResult;
import com.sw.ck.bpm.api.nodefunc.ResultFunction;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.Map;
import java.util.Optional;

/**
 * 内建结果函数：auditTrail —— 产物为纯审计解释摘要与受限白名单变量，
 * 不修改流程走向、不变更任务状态、不触碰主表单。
 */
@Component("func_audit_trail")
public class AuditTrailResultFunction implements ResultFunction {

    @Override
    public Optional<NodeFunctionResult> handleResult(NodeFunctionContext context,
                                                     Map<String, Object> nodeResult) {
        String summary = "audit:" + safe(context.getProcessInstanceId()) + ":"
                + safe(context.getNodeKey()) + ":outcome="
                + safe(nodeResult.get("outcome"));
        return Optional.of(NodeFunctionResult.builder()
                .summary(summary)
                .resultVariables(Map.of("audit_note", summary))
                .build());
    }

    private String safe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
