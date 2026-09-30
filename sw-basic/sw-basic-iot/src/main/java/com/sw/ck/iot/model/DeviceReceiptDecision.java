package com.sw.ck.iot.model;

/**
 * 回执/人工核实裁决结果。
 */
public record DeviceReceiptDecision(
        Verdict verdict,
        Long commandId,
        String statusBefore,
        String statusAfter) {

    public enum Verdict {
        /** 已收敛（状态转换完成）。 */
        APPLIED,
        /** 重复回执（同命令+请求+方向已受理），幂等无变化。 */
        DUPLICATE,
        /** 冲突回执：已确定结果不得覆盖，留审计。 */
        CONFLICT,
        /** 拒绝（来源/关联/状态前置不合法）。 */
        REJECTED
    }
}
