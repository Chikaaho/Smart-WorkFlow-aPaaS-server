package com.sw.ck.iot.service;

import com.sw.ck.iot.model.DeviceReceipt;
import com.sw.ck.iot.model.DeviceReceiptDecision;
import com.sw.ck.iot.model.ManualVerifyRequest;

/**
 * 设备命令结果回执与人工核实（P62 分级执行 S4，U04/U07）。
 * <p>
 * 回调守卫：来源签名、命令关联与租户边界、状态转换合法性、去重——
 * 迟到合法回执可收敛 {@code UNKNOWN}；冲突回执留审计，不得覆盖已确定结果。
 * 人工核实：仅 {@code UNKNOWN} 命令、必须携带可信依据、要求独立核实权限，
 * 记录依据/操作者/时间及前后状态；无厂商查询能力时的受控核实边界。
 * </p>
 */
public interface DeviceReceiptService {

    /**
     * 应用设备结果回执（经守卫裁决）。
     *
     * @param receipt 已通过来源签名校验的回执（命令标识、请求标识、结果方向、输出）
     * @return 裁决结果（APPLIED 收敛 / DUPLICATE 去重 / CONFLICT 冲突留审计 / REJECTED 拒绝）
     */
    DeviceReceiptDecision applyReceipt(DeviceReceipt receipt);

    /**
     * 独立授权人工核实 {@code UNKNOWN} 命令。
     *
     * @param commandId 命令标识
     * @param request   核实请求（结果方向与可信依据；依据缺失拒绝）
     * @return 裁决结果（APPLIED 收敛 / REJECTED 拒绝）
     */
    DeviceReceiptDecision verifyManually(Long commandId, ManualVerifyRequest request);
}
