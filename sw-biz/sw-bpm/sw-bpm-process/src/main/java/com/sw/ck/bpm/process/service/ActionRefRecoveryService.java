package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.port.FlowStartPortImpl;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 动作意图受控恢复（复审05 P1-06b）：按 ORCH / FLOW_START 命令的**持久状态**给出
 * 可诊断结果或受控恢复动作，替代原"重试端点 500 死路"。
 * <p>
 * 分支口径（ORCH = 意图命令，FLOW_START = 二段发起命令）：
 * <ul>
 *   <li>ORCH FAILED：复用同键命令重置入队（既有语义，原行可查）；</li>
 *   <li>ORCH EXPIRED：登记 ORCH 恢复代新行（原行/原载荷保留，不篡改）；</li>
 *   <li>ORCH PENDING/PROCESSING：返回处理中诊断，不产生新行；</li>
 *   <li>ORCH COMPLETED（二段窗口）：检查 FLOW_START 链（原键 + :R{n} 最新代）——
 *       失败/过期 → 按当前有效绑定登记新的 FLOW_START 恢复代（载荷=原输入+当前绑定指针，
 *       不改历史行）；无有效绑定 → 明确安全处置（零目标，不冒称成功）；处理中 → 诊断返回。</li>
 * </ul>
 * 目标实例已存在（已成功启动）时不做任何恢复（幂等保护）。
 * </p>
 */
@Service
public class ActionRefRecoveryService {

    /** 恢复结果：status 供前端/回查展示，message 为可读诊断，commandId 为相关命令（无则 null）。 */
    public record RetryOutcome(String status, String message, Long commandId) {
    }

    private static final Logger log = LoggerFactory.getLogger(ActionRefRecoveryService.class);

    private final BpmCommandQueue commandQueue;
    private final CommandRetryService commandRetryService;
    private final FlowStartPortImpl flowStartPort;
    private final BpmActionRefMapper actionRefMapper;
    private final BpmInstanceService bpmInstanceService;
    private final ObjectMapper objectMapper;

    public ActionRefRecoveryService(BpmCommandQueue commandQueue,
                                    CommandRetryService commandRetryService,
                                    FlowStartPortImpl flowStartPort,
                                    BpmActionRefMapper actionRefMapper,
                                    BpmInstanceService bpmInstanceService,
                                    ObjectMapper objectMapper) {
        this.commandQueue = commandQueue;
        this.commandRetryService = commandRetryService;
        this.flowStartPort = flowStartPort;
        this.actionRefMapper = actionRefMapper;
        this.bpmInstanceService = bpmInstanceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 受控恢复入口（事务壳：恢复行登记与意图状态更新同事务提交）。
     */
    @Transactional
    public RetryOutcome retry(BpmActionRef ref) {
        // 幂等保护：目标实例已存在 = 已成功启动，任何状态都不再做恢复动作
        if (ref.getTargetRecordId() != null && !ref.getTargetRecordId().isBlank()
                && bpmInstanceService.findByBusinessKey(ref.getTargetRecordId()).isPresent()) {
            return new RetryOutcome("ALREADY_STARTED",
                    "动作意图已成功启动（目标实例已存在），无需恢复", null);
        }
        CommandEnvelope orch = latestCommand(ref.getTenantId(), ref.getCommandKey());
        if (orch == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(),
                    "原命令不存在: " + ref.getCommandKey());
        }
        String status = orch.getStatus();
        if (CommandStatusEnum.PENDING.getCode().equals(status)
                || CommandStatusEnum.PROCESSING.getCode().equals(status)) {
            return new RetryOutcome("PROCESSING",
                    "原命令仍在处理中，暂不可恢复；请稍后按持久状态回查", orch.getCommandId());
        }
        if (CommandStatusEnum.FAILED.getCode().equals(status)) {
            Long reused = commandRetryService.requeueFailed(orch);
            markRef(ref, "INTENT_SUBMITTED", null);
            return new RetryOutcome("INTENT_SUBMITTED",
                    "失败意图已重新入队（复用同键命令，原失败记录保留）", reused);
        }
        if (CommandStatusEnum.EXPIRED.getCode().equals(status)) {
            Long next = enqueueOrchGeneration(ref, orch);
            markRef(ref, "INTENT_SUBMITTED", null);
            return new RetryOutcome("INTENT_SUBMITTED",
                    "过期意图已按恢复代重新受理（原过期行保留）", next);
        }
        // COMPLETED：二段 FLOW_START 窗口
        return retryFlowStartWindow(ref);
    }

    private RetryOutcome retryFlowStartWindow(BpmActionRef ref) {
        if (ref.getTargetRecordId() == null || ref.getTargetRecordId().isBlank()) {
            return new RetryOutcome("NO_TARGET_RECORD",
                    "意图已完成但未登记目标记录，无可恢复对象；请管理员核查", null);
        }
        String baseKey = "FLOW_START:" + ref.getTargetRecordId();
        CommandEnvelope flow = latestCommand(ref.getTenantId(), baseKey);
        if (flow == null) {
            return new RetryOutcome("FLOW_START_MISSING",
                    "未找到流程发起命令（目标记录=" + ref.getTargetRecordId() + "），请管理员核查", null);
        }
        String status = flow.getStatus();
        if (CommandStatusEnum.PENDING.getCode().equals(status)
                || CommandStatusEnum.PROCESSING.getCode().equals(status)) {
            return new RetryOutcome("PROCESSING",
                    "流程发起仍在处理中；请稍后按持久状态回查", flow.getCommandId());
        }
        Map<String, Object> payload = parsePayload(flow.getPayload());
        Optional<Long> next = flowStartPort.recoverFlowStart(ref.getTenantId(), payload);
        if (next.isEmpty()) {
            return new RetryOutcome("DISPOSED_NO_BINDING",
                    "表单当前无启用流程绑定：按明确安全处置零目标启动；如需发起请先恢复绑定后再次恢复",
                    null);
        }
        markRef(ref, "STARTING", null);
        String verb = CommandStatusEnum.COMPLETED.getCode().equals(status)
                ? "零目标处置后按当前绑定重新受理"
                : "已按当前绑定受理恢复发起";
        return new RetryOutcome("RECOVERY_ENQUEUED",
                verb + "（目标实例由既有消费链创建，原失败行保留）", next.orElseThrow());
    }

    /** ORCH 恢复代：新键 {@code {原键}:R{n}}，载荷/指纹沿用原行（不改历史行、不重复意图语义）。 */
    private Long enqueueOrchGeneration(BpmActionRef ref, CommandEnvelope expired) {
        String baseKey = ref.getCommandKey();
        int generation = 1;
        String key;
        while (true) {
            key = baseKey + ":R" + generation;
            if (commandQueue.findByKey(ref.getTenantId(), key).isEmpty()) {
                break;
            }
            generation++;
        }
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.ORCH_ACTION_START);
        envelope.setChannel(expired.getChannel());
        envelope.setCommandKey(key);
        envelope.setTenantId(ref.getTenantId());
        envelope.setInitiatorId(expired.getInitiatorId());
        envelope.setPayload(expired.getPayload());
        envelope.setPayloadFingerprint(expired.getPayloadFingerprint());
        envelope.setCompletionPoint(expired.getCompletionPoint());
        log.warn("ORCH 意图过期恢复代已受理: refId={}, recoveryKey={}", ref.getId(), key);
        return commandQueue.enqueue(envelope);
    }

    /** 沿 {@code :R{n}} 取最新代命令（含原始键；全缺返回 null）。 */
    private CommandEnvelope latestCommand(Long tenantId, String baseKey) {
        CommandEnvelope latest = commandQueue.findByKey(tenantId, baseKey).orElse(null);
        int generation = 1;
        while (true) {
            Optional<CommandEnvelope> next = commandQueue.findByKey(tenantId, baseKey + ":R" + generation);
            if (next.isEmpty()) {
                break;
            }
            latest = next.get();
            generation++;
        }
        return latest;
    }

    private void markRef(BpmActionRef ref, String status, String errorText) {
        ref.setStatus(status);
        ref.setErrorText(errorText);
        actionRefMapper.updateById(ref);
    }

    private Map<String, Object> parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload == null ? "{}" : payload,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.TRIGGER_INVALID.getCode(),
                    "流程发起载荷解析失败: " + e.getMessage());
        }
    }
}
