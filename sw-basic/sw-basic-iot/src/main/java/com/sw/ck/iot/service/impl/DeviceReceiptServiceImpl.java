package com.sw.ck.iot.service.impl;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.model.DeviceReceipt;
import com.sw.ck.iot.model.DeviceReceiptDecision;
import com.sw.ck.iot.model.ManualVerifyRequest;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.DeviceReceiptService;
import com.sw.ck.iot.service.IotAuditService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * {@link DeviceReceiptService} 实现：守卫裁决全部经真实命令行状态机与审计落地。
 * <p>
 * 转换矩阵：非终态（SENT/DELIVERED/ACKED）与 {@code UNKNOWN} 可被确定回执收敛；
 * 已确定结果（SUCCESS/FAILED/EXPIRED）不覆盖——同向幂等去重、反向留 CONFLICT 审计；
 * 未发出命令（QUEUED/SENDING）的回执拒绝。人工核实仅接受 {@code UNKNOWN} 且依据必填，
 * 权限与调用方一致采用 {@code iot:command:verify}（超管按其语义旁路）。
 * </p>
 */
@Service
public class DeviceReceiptServiceImpl implements DeviceReceiptService {

    private static final Logger log = LoggerFactory.getLogger(DeviceReceiptServiceImpl.class);

    /** 与 TxnActionController/BpmCommandController 相同的注解权限手法；服务内复校防内部绕过。 */
    public static final String VERIFY_PERMISSION = "iot:command:verify";

    private static final Set<String> RECEIPT_READY = Set.of("SENT", "DELIVERED", "ACKED", "UNKNOWN");
    private static final Set<String> DETERMINED = Set.of("SUCCESS", "FAILED", "EXPIRED");
    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILED");

    private final IotDeviceCommandMapper commandMapper;
    private final CommandQueueService commandQueueService;
    private final IotAuditService auditService;

    @Value("${sw.iot.receipt.secret:}")
    private String receiptSecret;

    public DeviceReceiptServiceImpl(IotDeviceCommandMapper commandMapper,
                                    CommandQueueService commandQueueService,
                                    IotAuditService auditService) {
        this.commandMapper = commandMapper;
        this.commandQueueService = commandQueueService;
        this.auditService = auditService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceReceiptDecision applyReceipt(DeviceReceipt receipt) {
        if (receipt == null || receipt.commandId() == null
                || receipt.requestId() == null || receipt.requestId().isBlank()
                || receipt.outcome() == null || !OUTCOMES.contains(receipt.outcome())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "回执载荷不完整（commandId/requestId/outcome）");
        }
        // 回执通道无登录态（外部传输对端，来源由 HMAC 守卫承担）：全程挂起租户拦截，
        // 命令行 tenant_id 自承载租户语义、主键定位命令行（与补偿调度跨租户扫描同一手法）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return applyReceiptSuspended(receipt);
        }
    }

    private DeviceReceiptDecision applyReceiptSuspended(DeviceReceipt receipt) {
        IotDeviceCommand command = commandMapper.selectById(receipt.commandId());
        if (command == null) {
            audit(null, receipt, "RECEIPT_REJECTED", "命令不存在",
                    String.valueOf(receipt.commandId()), "commandId=" + receipt.commandId());
            return rejected(receipt.commandId(), null);
        }

        String before = command.getStatus();
        if (DETERMINED.contains(before)) {
            // 已确定结果不覆盖：同向幂等去重；反向留冲突审计
            String determined = "SUCCESS".equals(before) ? "SUCCESS" : "FAILED";
            if (receipt.outcome().equals(determined) || "EXPIRED".equals(before)) {
                audit(command.getTenantId(), receipt, "RECEIPT_DUPLICATE", before,
                        String.valueOf(command.getId()), "before=" + before + ",after=" + before);
                return new DeviceReceiptDecision(DeviceReceiptDecision.Verdict.DUPLICATE,
                        command.getId(), before, before);
            }
            audit(command.getTenantId(), receipt, "RECEIPT_CONFLICT", before,
                    String.valueOf(command.getId()),
                    "已确定结果不被覆盖: before=" + before + ",receipt=" + receipt.outcome());
            return new DeviceReceiptDecision(DeviceReceiptDecision.Verdict.CONFLICT,
                    command.getId(), before, before);
        }
        if (!RECEIPT_READY.contains(before)) {
            // QUEUED/SENDING 未真实发出：回执前置不合法
            audit(command.getTenantId(), receipt, "RECEIPT_REJECTED", before,
                    String.valueOf(command.getId()), "命令未发出，回执前置不合法: before=" + before);
            return rejected(command.getId(), before);
        }

        applyOutcome(command, receipt.outcome(), receipt.output(), "RECEIPT", receipt.requestId(), null);
        audit(command.getTenantId(), receipt, "RECEIPT_APPLIED", before,
                String.valueOf(command.getId()), "before=" + before + ",after=" + receipt.outcome());
        return new DeviceReceiptDecision(DeviceReceiptDecision.Verdict.APPLIED,
                command.getId(), before, receipt.outcome());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceReceiptDecision verifyManually(Long commandId, ManualVerifyRequest request) {
        requireVerifier();
        if (request == null || request.outcome() == null || !OUTCOMES.contains(request.outcome())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "人工核实结果方向不合法（SUCCESS/FAILED）");
        }
        if (request.basis() == null || request.basis().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "人工核实必须携带可信依据（无依据不得宣告结果）");
        }
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }
        String before = command.getStatus();
        if (!"UNKNOWN".equals(before)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "仅结果未知（UNKNOWN）的命令可人工核实: 当前 " + before);
        }
        LoginUser operator = LoginUserHolder.get();
        applyOutcome(command, request.outcome(), null, "MANUAL_VERIFY",
                "VERIFY:" + command.getId(),
                "{\"basis\":\"" + escape(request.basis())
                        + "\",\"operatorId\":" + operator.getUserId() + "}");
        audit(command.getTenantId(), null, "COMMAND_MANUAL_VERIFY", before,
                String.valueOf(command.getId()),
                "before=UNKNOWN,after=" + request.outcome() + ",basis=" + request.basis());
        return new DeviceReceiptDecision(DeviceReceiptDecision.Verdict.APPLIED,
                command.getId(), before, request.outcome());
    }

    // ==================== 内部 ====================

    private void applyOutcome(IotDeviceCommand command, String outcome, String output,
                              String source, String requestId, String verifyDetail) {
        if ("SUCCESS".equals(outcome)) {
            command.setStatus("SUCCESS");
            command.setResult(serializeResult(source, requestId, output, verifyDetail));
            command.setLastError(null);
        } else {
            command.setStatus("FAILED");
            command.setResult(serializeResult(source, requestId, output, verifyDetail));
            command.setLastError("由 " + source + " 收敛为 FAILED");
        }
        commandMapper.updateById(command);
        log.info("设备命令结果已收敛: id={}, source={}, outcome={}", command.getId(), source, outcome);
    }

    private String serializeResult(String source, String requestId, String output, String verifyDetail) {
        StringBuilder json = new StringBuilder("{\"source\":\"").append(source).append('"');
        if (requestId != null) {
            json.append(",\"requestId\":\"").append(escape(requestId)).append('"');
        }
        if (output != null && !output.isBlank()) {
            json.append(",\"output\":\"").append(escape(output)).append('"');
        }
        if (verifyDetail != null) {
            json.append(',').append(verifyDetail.trim().replaceFirst("^\\{", "").replaceFirst("}$", ""));
        }
        return json.append('}').toString();
    }

    /** 来源签名校验（HMAC-SHA256；与回调载荷同源密钥）。 */
    public void assertValidSignature(DeviceReceipt receipt, String signature) {
        if (receiptSecret == null || receiptSecret.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "回执签名密钥未配置，回执通道关闭");
        }
        if (signature == null || signature.isBlank()) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "缺少回执签名");
        }
        String expected = hmacSha256(receiptSecret, canonical(receipt));
        if (!constantTimeEquals(expected, signature)) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "回执签名校验失败");
        }
    }

    private String canonical(DeviceReceipt receipt) {
        return receipt.commandId() + "|" + receipt.requestId() + "|" + receipt.outcome()
                + "|" + (receipt.output() == null ? "" : receipt.output())
                + "|" + (receipt.tenantKey() == null ? "" : receipt.tenantKey());
    }

    private static String hmacSha256(String secret, String message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("回执签名计算失败", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void requireVerifier() {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        boolean allowed = user.isSuperAdmin()
                || (user.getPermissions() != null && user.getPermissions().contains(VERIFY_PERMISSION));
        if (!allowed) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(),
                    "无权人工核实：缺少 " + VERIFY_PERMISSION + "（仅监控查看权限不足以改结果）");
        }
    }

    private void audit(Long tenantId, DeviceReceipt receipt, String action, String result,
                       String objectId, String detail) {
        try {
            auditService.recordAction(tenantId, null, receipt == null ? "system:iot-receipt" : "system:iot-receipt",
                    action, "IOT_DEVICE_COMMAND",
                    objectId == null ? String.valueOf(receipt == null ? null : receipt.commandId()) : objectId,
                    result, receipt == null ? null : receipt.requestId(), detail);
        } catch (RuntimeException e) {
            // 审计失败不阻断守卫主路径，但必须显式暴露（审计缺口可见）
            log.error("回执审计写入失败: action={}, commandId={}", action,
                    receipt == null ? null : receipt.commandId(), e);
        }
    }

    private DeviceReceiptDecision rejected(Long commandId, String before) {
        return new DeviceReceiptDecision(DeviceReceiptDecision.Verdict.REJECTED, commandId, before, before);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
