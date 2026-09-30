package com.sw.ck.form.txn.model;

import lombok.Data;

import java.util.Map;

/**
 * 动作调用请求。
 * <p>invocationKey 为可选稳定幂等键：相同键 + 相同请求指纹返回原结果；相同键 + 不同指纹明确冲突。</p>
 */
@Data
public class TxnInvokeRequest {

    /** 稳定幂等键（可选；缺省时不提供幂等保护） */
    private String invocationKey;

    /** 目标记录 id（RESERVE/ADJUST） */
    private String recordId;

    /** 预占凭据 id（CONFIRM/RELEASE） */
    private String reservationId;

    /** 数量（十进制字符串，精度按模型声明校验） */
    private String quantity;

    /** 期望数据版本（可选；提供时不一致返回版本冲突） */
    private Long expectedVersion;

    /** 业务键取值（可选；须为声明 keyFields 的子集） */
    private Map<String, Object> businessKeys;
}
