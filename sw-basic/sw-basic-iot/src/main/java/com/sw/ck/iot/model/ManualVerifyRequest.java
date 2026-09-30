package com.sw.ck.iot.model;

/**
 * 独立授权人工核实请求（U04：必须携带可信依据；仅 UNKNOWN 命令可核实）。
 */
public record ManualVerifyRequest(
        /** 结果方向：SUCCESS / FAILED。 */
        String outcome,
        /** 可信依据（现场照片编号、工单号、厂商后台截图编号等），缺失拒绝。 */
        String basis) {
}
