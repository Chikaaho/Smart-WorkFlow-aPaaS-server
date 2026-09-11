package com.sw.ck.bpm.process.nodefunc;

import com.sw.ck.bpm.api.nodefunc.NodeFunctionContext;
import com.sw.ck.bpm.api.nodefunc.NodeFunctionResult;
import com.sw.ck.bpm.api.nodefunc.ResultFunction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内建结果函数：resultEcho（V75，G13b）—— 把受限结果变量写回流程白名单
 * （audit_note / echo_outcome），供证据链核对"正向写白名单变量"。
 */
@Component("func_result_echo")
public class ResultEchoFunction implements ResultFunction {

    @Override
    public NodeFunctionResult handleResult(NodeFunctionContext context,
                                           Map<String, Object> nodeResult) {
        String summary = "echo:" + context.getProcessInstanceId() + ":"
                + context.getNodeKey() + ":outcome=" + nodeResult.get("outcome");
        return NodeFunctionResult.builder()
                .summary(summary)
                .resultVariables(Map.of("audit_note", summary))
                .build();
    }
}
