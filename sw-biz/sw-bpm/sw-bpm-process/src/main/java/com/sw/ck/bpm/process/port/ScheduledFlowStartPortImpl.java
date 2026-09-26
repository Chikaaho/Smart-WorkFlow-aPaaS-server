package com.sw.ck.bpm.process.port;

import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.job.event.ScheduledFlowTriggerEvent;
import com.sw.ck.job.port.ScheduledFlowStartPort;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 定时任务流程启动受理端口实现（Phase 4：可靠业务事件 —— Scheduled FLOW）。
 *
 * <p>实现方式与 {@link FlowStartPortImpl} 同构：在<b>调用方（调度器）事务内</b>把启动意图
 * 写入持久命令队列 {@code sw_bpm_command}，由领取者以 {@code jobId + fireTime} 为幂等键
 * 最终启动一次流程。因此：</p>
 * <ul>
 *   <li>调度器事务回滚 → 意图不存在，不会留下可执行孤儿任务；</li>
 *   <li>调度器事务提交后进程退出 → 命令仍是 PENDING，重启后仍可领取（含 stale 回收）；</li>
 *   <li>重复触发同一次触发时间 → 同一 command_key 唯一索引命中，回查返回既有命令 ID。</li>
 * </ul>
 *
 * <p>绑定解析按流程定义键（{@code flow_def_key}）反向查启用绑定：无绑定返回 empty
 * （合法 no-op，由调度器记为明确失败，不得伪装成功）；多于一条绑定属配置冲突，抛明确异常。</p>
 */
@Component
public class ScheduledFlowStartPortImpl implements ScheduledFlowStartPort {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFlowStartPortImpl.class);

    /** 幂等命令键前缀：同一 jobId + fireTime 只允许一条命令。 */
    static final String COMMAND_KEY_PREFIX = "SCHEDULED_FLOW:";

    /** 定时发起实例的业务键前缀（业务键列上限 36 字符）。 */
    private static final String BUSINESS_KEY_PREFIX = "sched-";

    private final BpmFormBindingService bindingService;
    private final BpmCommandQueue commandQueue;
    private final ObjectMapper objectMapper;

    public ScheduledFlowStartPortImpl(BpmFormBindingService bindingService,
                                      BpmCommandQueue commandQueue,
                                      ObjectMapper objectMapper) {
        this.bindingService = bindingService;
        this.commandQueue = commandQueue;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Long> acceptScheduledFlowStart(ScheduledFlowTriggerEvent event) {
        requireIdentity(event);
        List<BpmFormBinding> bindings = findActiveBindings(event.getFlowDefKey());
        if (bindings.isEmpty()) {
            log.warn("定时任务 {} 的流程键 {} 无启用绑定，不受理启动（不构成成功）",
                    event.getJobId(), event.getFlowDefKey());
            return Optional.empty();
        }
        if (bindings.size() > 1) {
            throw new IllegalStateException("流程键存在多个有效表单绑定，无法确定发起目标: processDefKey="
                    + event.getFlowDefKey() + ", bindings=" + bindings.size());
        }
        BpmFormBinding binding = bindings.get(0);
        String commandKey = commandKey(event);
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.SCHEDULED_FLOW_START);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey(commandKey);
        envelope.setTenantId(event.getTenantId());
        envelope.setInitiatorId(event.getConfiguredBy());
        envelope.setPayload(toPayload(event, binding));
        try {
            Long commandId = commandQueue.enqueue(envelope);
            log.info("定时流程启动意图已持久化: jobId={}, fireTime={}, commandKey={}, commandId={}",
                    event.getJobId(), event.getFireTime(), commandKey, commandId);
            return Optional.of(commandId);
        } catch (DuplicateKeyException e) {
            return commandQueue.findByKey(event.getTenantId(), commandKey)
                    .map(existing -> {
                        log.info("定时流程启动意图幂等命中: commandKey={}, commandId={}",
                                commandKey, existing.getCommandId());
                        return Optional.of(existing.getCommandId());
                    })
                    .orElseGet(() -> {
                        log.warn("定时流程启动命令幂等键冲突但回查不可见: tenantId={}, commandKey={}",
                                event.getTenantId(), commandKey);
                        return Optional.empty();
                    });
        }
    }

    /** 幂等命令键：任务身份 + 触发时间（稳定业务身份，不随机）。 */
    public static String commandKey(ScheduledFlowTriggerEvent event) {
        return COMMAND_KEY_PREFIX + event.getJobId() + ":" + event.fireTimeEpochMillis();
    }

    /**
     * 定时发起的流程实例业务键：稳定派生自 jobId + fireTime，重放不产生第二条实例。
     */
    public static String businessKey(ScheduledFlowTriggerEvent event) {
        return BUSINESS_KEY_PREFIX + shortHash(event.getJobId() + ":" + event.fireTimeEpochMillis());
    }

    public static String businessKey(String jobIdAndFireTime) {
        return BUSINESS_KEY_PREFIX + shortHash(jobIdAndFireTime);
    }

    private static String shortHash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 30);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算定时发起业务键", e);
        }
    }

    private void requireIdentity(ScheduledFlowTriggerEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("定时流程启动意图不可为空");
        }
        if (event.getJobId() == null || event.getFlowDefKey() == null || event.getFlowDefKey().isBlank()) {
            throw new IllegalArgumentException("定时流程启动意图缺少任务或流程键: " + event);
        }
        if (event.getTenantId() == null) {
            throw new IllegalArgumentException("定时流程启动意图缺少租户: " + event);
        }
        if (event.fireTimeEpochMillis() == 0L) {
            throw new IllegalArgumentException("定时流程启动意图缺少触发时间（幂等身份不足）: " + event);
        }
        if (event.getConfiguredBy() == null) {
            throw new IllegalArgumentException(
                    "定时流程启动意图缺少任务配置人（流程发起人身份）: jobId=" + event.getJobId());
        }
    }

    private List<BpmFormBinding> findActiveBindings(String processDefKey) {
        return bindingService.lambdaQuery()
                .eq(BpmFormBinding::getProcessDefKey, processDefKey)
                .eq(BpmFormBinding::getActive, Boolean.TRUE)
                .list();
    }

    private String toPayload(ScheduledFlowTriggerEvent event, BpmFormBinding binding) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", event.getJobId());
        payload.put("fireTimeEpochMillis", event.fireTimeEpochMillis());
        payload.put("processDefKey", binding.getProcessDefKey());
        payload.put("formKey", binding.getFormKey());
        payload.put("formDataJson", event.getFormData());
        payload.put("configuredBy", event.getConfiguredBy());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("定时流程启动意图序列化失败: jobId=" + event.getJobId(), e);
        }
    }

    /** 供命令处理器复用的业务键计算（入参为 payload 中的 identity 串）。 */
    public static String businessKeyForPayload(Object jobId, Object fireTimeEpochMillis) {
        return businessKey(String.valueOf(jobId) + ":" + String.valueOf(fireTimeEpochMillis));
    }

    /** 供命令处理器构造发起命令（与手动提交共用 {@link StartCommand} 契约）。 */
    public static StartCommand toStartCommand(Map<String, Object> payload, Long tenantId, Long configuredBy) {
        StartCommand cmd = new StartCommand();
        cmd.setFormKey(asText(payload.get("formKey")));
        cmd.setProcessDefKey(asText(payload.get("processDefKey")));
        cmd.setRecordId(businessKeyForPayload(payload.get("jobId"), payload.get("fireTimeEpochMillis")));
        cmd.setSubmitter(configuredBy);
        cmd.setTenantId(tenantId);
        cmd.setSubmittedData(parseFormData(payload.get("formDataJson")));
        return cmd;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFormData(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return new ObjectMapper().readValue(text, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("定时任务表单数据不是合法 JSON 对象", e);
        }
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
