package com.sw.ck.iot.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.model.DeviceReceipt;
import com.sw.ck.iot.model.DeviceReceiptDecision;
import com.sw.ck.iot.model.ManualVerifyRequest;
import com.sw.ck.iot.service.DeviceReceiptService;
import com.sw.ck.iot.service.impl.DeviceReceiptServiceImpl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备命令结果回执与人工核实入口（P62 分级执行 S4）。
 * <p>
 * {@code POST /iot/commands/receipt}：外部传输对端回调通道——不走登录态，
 * 以 HMAC-SHA256 来源签名守卫（密钥 {@code sw.iot.receipt.secret}），
 * 裁决（收敛/去重/冲突/拒绝）由 {@link DeviceReceiptService} 落状态机与审计。
 * {@code POST /iot/commands/{id}/manual-verify}：独立授权人工核实，
 * 仅 {@code UNKNOWN} 命令、必须携带可信依据（仅 {@code iot:view}/monitor 视角不足改结果）。
 * </p>
 */
@RestController
@RequestMapping("/iot/commands")
public class IotDeviceReceiptController {

    private final DeviceReceiptService deviceReceiptService;
    private final DeviceReceiptServiceImpl signatureVerifier;

    public IotDeviceReceiptController(DeviceReceiptService deviceReceiptService,
                                      DeviceReceiptServiceImpl signatureVerifier) {
        this.deviceReceiptService = deviceReceiptService;
        this.signatureVerifier = signatureVerifier;
    }

    /** 外部传输对端回执（来源签名守卫；无登录态）。 */
    @PostMapping("/receipt")
    public R<DeviceReceiptDecision> receipt(@RequestHeader(value = "X-Sw-Receipt-Signature", required = false)
                                            String signature,
                                            @RequestBody DeviceReceipt receipt) {
        signatureVerifier.assertValidSignature(receipt, signature);
        return R.ok(deviceReceiptService.applyReceipt(receipt));
    }

    /** 独立授权人工核实（最小权限 iot:command:verify；仅 UNKNOWN；依据必填）。 */
    @PostMapping("/{commandId}/manual-verify")
    @PreAuthorize("@ss.hasPermi('iot:command:verify')")
    public R<DeviceReceiptDecision> manualVerify(@PathVariable Long commandId,
                                                 @RequestBody ManualVerifyRequest request) {
        return R.ok(deviceReceiptService.verifyManually(commandId, request));
    }
}
