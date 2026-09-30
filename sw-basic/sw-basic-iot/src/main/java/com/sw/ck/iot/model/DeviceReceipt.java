package com.sw.ck.iot.model;

/**
 * 设备结果回执（传输对端回调载荷，经来源签名校验后进入守卫）。
 */
public record DeviceReceipt(
        Long commandId,
        String requestId,
        /** 结果方向：SUCCESS / FAILED。 */
        String outcome,
        String output,
        String tenantKey) {
}
