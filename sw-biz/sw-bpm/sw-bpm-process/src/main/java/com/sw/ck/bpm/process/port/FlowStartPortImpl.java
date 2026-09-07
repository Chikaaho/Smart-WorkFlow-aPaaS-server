package com.sw.ck.bpm.process.port;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.form.api.event.FormSubmittedEvent;
import com.sw.ck.form.api.port.FlowStartPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link FlowStartPort} 默认实现：表单提交事务内持久化流程发起受理。
 * <p>
 * 无启用绑定时 no-op 返回 null（与既有 ProcessStartService 语义一致）；
 * 同一 recordId 重复受理（幂等键冲突）返回既有受理标识，不产生第二条命令。
 * </p>
 */
@Service
public class FlowStartPortImpl implements FlowStartPort {

    private static final Logger log = LoggerFactory.getLogger(FlowStartPortImpl.class);

    private final BpmFormBindingService bindingService;
    private final BpmCommandQueue commandQueue;
    private final ObjectMapper objectMapper;

    public FlowStartPortImpl(BpmFormBindingService bindingService,
                             BpmCommandQueue commandQueue,
                             ObjectMapper objectMapper) {
        this.bindingService = bindingService;
        this.commandQueue = commandQueue;
        this.objectMapper = objectMapper;
    }

    @Override
    public Long acceptFlowStart(FormSubmittedEvent event) {
        var bindings = bindingService.findActiveByFormKey(event.getFormKey());
        if (bindings.isEmpty()) {
            log.debug("表单 {} 无启用绑定，不受理流程发起", event.getFormKey());
            return null;
        }
        if (bindings.size() != 1) {
            throw new IllegalStateException("表单存在多个有效流程绑定: formKey=" + event.getFormKey());
        }
        String resolvedProcessDefKey = bindings.get(0).getProcessDefKey();
        if (event.getProcessDefKey() != null && !event.getProcessDefKey().isBlank()
                && !event.getProcessDefKey().equals(resolvedProcessDefKey)) {
            throw new IllegalStateException("表单流程绑定已变化: formKey=" + event.getFormKey());
        }
        String commandKey = "FLOW_START:" + event.getRecordId();
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.FLOW_START);
        envelope.setChannel(resolveChannel(event.getDispatchChannel()));
        envelope.setCommandKey(commandKey);
        envelope.setTenantId(event.getTenantId());
        envelope.setInitiatorId(Long.valueOf(event.getSubmitter()));
        envelope.setPayload(toPayload(event, resolvedProcessDefKey));
        try {
            return commandQueue.enqueue(envelope);
        } catch (DuplicateKeyException e) {
            // 幂等：同一提交意图的重复受理返回同一结果
            return commandQueue.findByKey(event.getTenantId(), commandKey)
                    .map(CommandEnvelope::getCommandId)
                    .orElse(null);
        }
    }

    /** StartCommand 消费方重建所需的最小 payload。 */
    public static Map<String, Object> buildPayload(StartCommand cmd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formKey", cmd.getFormKey());
        payload.put("recordId", cmd.getRecordId());
        payload.put("submitter", cmd.getSubmitter());
        payload.put("submittedData", cmd.getSubmittedData());
        return payload;
    }

    public static StartCommand toStartCommand(CommandEnvelope envelope, Map<String, Object> payload) {
        StartCommand cmd = new StartCommand();
        cmd.setFormKey((String) payload.get("formKey"));
        cmd.setProcessDefKey((String) payload.get("processDefKey"));
        cmd.setRecordId((String) payload.get("recordId"));
        Object submitter = payload.get("submitter");
        cmd.setSubmitter(submitter == null ? envelope.getInitiatorId()
                : Long.valueOf(String.valueOf(submitter)));
        cmd.setTenantId(envelope.getTenantId());
        Object data = payload.get("submittedData");
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> submitted = new LinkedHashMap<>();
            map.forEach((k, v) -> submitted.put(String.valueOf(k), v));
            cmd.setSubmittedData(submitted);
        }
        return cmd;
    }

    private String toPayload(FormSubmittedEvent event, String resolvedProcessDefKey) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("formKey", event.getFormKey());
            payload.put("processDefKey", resolvedProcessDefKey);
            payload.put("recordId", event.getRecordId());
            payload.put("submitter", event.getSubmitter());
            payload.put("submittedData", event.getSubmittedData() == null
                    ? Map.of() : event.getSubmittedData());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化流程发起 payload 失败: recordId=" + event.getRecordId(), e);
        }
    }

    private CommandChannelEnum resolveChannel(String dispatchChannel) {
        if (dispatchChannel == null || dispatchChannel.isBlank()) {
            return CommandChannelEnum.NORMAL;
        }
        try {
            return CommandChannelEnum.valueOf(dispatchChannel.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("未知流程发起通道: " + dispatchChannel);
        }
    }
}
