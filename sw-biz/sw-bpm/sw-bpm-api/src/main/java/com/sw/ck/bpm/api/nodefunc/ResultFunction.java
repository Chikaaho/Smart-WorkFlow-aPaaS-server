package com.sw.ck.bpm.api.nodefunc;

import java.util.Map;

/** 节点结果处理函数契约（I3 §4.9）。输出不得绕过节点状态机、表单校验或审批权限。 */
public interface ResultFunction {

    NodeFunctionResult handleResult(NodeFunctionContext context,
                                    Map<String, Object> nodeResult);
}
