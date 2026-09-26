package com.sw.ck.bpm.api.nodefunc;

import java.util.Map;
import java.util.Optional;

/** 节点结果处理函数契约（I3 §4.9）。输出不得绕过节点状态机、表单校验或审批权限。 */
public interface ResultFunction {

    /**
     * 处理节点结果。
     *
     * @return present = 函数输出（含参与人、摘要与白名单结果变量）；当前契约恒 present，
     *         处理失败抛明确异常
     */
    Optional<NodeFunctionResult> handleResult(NodeFunctionContext context,
                                              Map<String, Object> nodeResult);
}
