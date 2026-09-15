package com.sw.ck.bpm.api.nodefunc;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 节点函数执行上下文（I3 §4.9）。
 * <p>
 * 字段为白名单：租户、发起人、流程/实例/节点、表单快照版本、既往节点结果与
 * 幂等标识。函数实现不得读取越权变量或跨租户对象。
 * </p>
 */
@Getter
@Builder
public class NodeFunctionContext {

    private Long tenantId;
    private String processInstanceId;
    private String nodeKey;
    private String nodeIdempotentKey;
    private String processDefKey;
    private Integer defVersion;
    private String formKey;
    private String formVersion;
    private Long initiatorUserId;
    private Long actorUserId;

    /** 白名单流程变量（既往节点结果/解析摘要），非全量变量透传。 */
    private Map<String, Object> variables;
}
