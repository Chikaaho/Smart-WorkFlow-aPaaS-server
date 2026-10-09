package com.sw.ck.bpm.process.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.port.FlowStartPortImpl;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.service.ProcessStartService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 流程发起命令处理器：消费 FLOW_START 受理，汇入 {@link ProcessStartService} 唯一入口。
 * <p>
 * 幂等：已存在同 businessKey（recordId）实例时跳过，重复消费不重复启动（D1/D5）。
 * 二段失败可诊断（复审05 P1-06b）：有界重试耗尽进入 FAILED 终态、或消费完成但零目标
 * 实例（绑定已移除的安全处置）时，把对应动作意图标记为可诊断失败（状态+原因），
 * 不让意图永久停留 STARTING 冒充进行中；恢复由有权用户经受控恢复路径执行。
 * </p>
 */
@Component
public class FlowStartCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(FlowStartCommandHandler.class);

    private final ProcessStartService processStartService;
    private final BpmInstanceService bpmInstanceService;
    private final BpmActionRefMapper actionRefMapper;
    private final ObjectMapper objectMapper;

    public FlowStartCommandHandler(ProcessStartService processStartService,
                                   BpmInstanceService bpmInstanceService,
                                   BpmActionRefMapper actionRefMapper,
                                   ObjectMapper objectMapper) {
        this.processStartService = processStartService;
        this.bpmInstanceService = bpmInstanceService;
        this.actionRefMapper = actionRefMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public java.util.Set<CommandTypeEnum> types() {
        return java.util.Set.of(CommandTypeEnum.FLOW_START);
    }

    @Override
    public String handle(CommandEnvelope envelope) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(
                envelope.getPayload() == null ? "{}" : envelope.getPayload(), Map.class);
        StartCommand cmd = FlowStartPortImpl.toStartCommand(envelope, payload);

        // 幂等：重复投递/恢复后不重复启动
        if (bpmInstanceService.findByBusinessKey(cmd.getRecordId()).isPresent()) {
            log.info("流程发起命令幂等跳过: recordId={} 已存在实例", cmd.getRecordId());
            return "{\"status\":\"SKIP_DUPLICATE\"}";
        }
        processStartService.start(cmd);
        // 消费完成但零目标实例（绑定已移除的合法 no-op 路径）：明确标记意图失败原因，
        // 不让意图永久停留 STARTING（复审05 P1-06b：失败结果可诊断，恢复走受控路径）。
        if (bpmInstanceService.findByBusinessKey(cmd.getRecordId()).isEmpty()) {
            markRefFailure(envelope, "目标流程绑定已移除，未创建目标实例（安全处置，零目标）");
            return "{\"status\":\"DISPOSED_NO_TARGET\"}";
        }
        return "{\"status\":\"STARTED\"}";
    }

    /**
     * 有界重试耗尽、命令进入 FAILED 终态后的补偿（复审05 P1-06b）：
     * 按 FLOW_START 载荷 recordId 定位仍处 STARTING 的对应意图，标记 FAILED + 失败原因。
     */
    @Override
    public void onFinalFailure(CommandEnvelope envelope, String reason) {
        markRefFailure(envelope, reason);
    }

    private void markRefFailure(CommandEnvelope envelope, String reason) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    envelope.getPayload() == null ? "{}" : envelope.getPayload(), Map.class);
            Object recordId = payload.get("recordId");
            if (recordId == null || String.valueOf(recordId).isBlank()) {
                return;
            }
            String text = reason == null ? null
                    : (reason.length() > 480 ? reason.substring(0, 480) : reason);
            // 调度线程无登录态：与命令行消费同口径挂起租户过滤，定位靠显式 tenant_id 条件
            try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                         com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
                int updated = actionRefMapper.update(null,
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<com.sw.ck.bpm.process.entity.BpmActionRef>lambdaUpdate()
                                .eq(com.sw.ck.bpm.process.entity.BpmActionRef::getTenantId,
                                        envelope.getTenantId())
                                .eq(com.sw.ck.bpm.process.entity.BpmActionRef::getTargetRecordId,
                                        String.valueOf(recordId))
                                .eq(com.sw.ck.bpm.process.entity.BpmActionRef::getStatus, "STARTING")
                                .set(com.sw.ck.bpm.process.entity.BpmActionRef::getStatus, "FAILED")
                                .set(com.sw.ck.bpm.process.entity.BpmActionRef::getErrorText, text));
                if (updated > 0) {
                    log.warn("二段流程发起失败，意图已标记可诊断失败: recordId={}, reason={}",
                            recordId, text);
                }
            }
        } catch (Exception e) {
            log.warn("意图失败标记写入跳过: recordId 解析或更新异常, error={}", e.getMessage());
        }
    }
}
