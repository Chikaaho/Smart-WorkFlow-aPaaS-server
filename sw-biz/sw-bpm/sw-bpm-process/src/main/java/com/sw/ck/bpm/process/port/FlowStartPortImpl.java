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
import java.util.Optional;

/**
 * {@link FlowStartPort} 默认实现：表单提交事务内持久化流程发起受理。
 * <p>
 * 无启用绑定时为合法 no-op，返回 {@code Optional.empty()}（与既有 ProcessStartService 语义一致）；
 * 同一 recordId 重复受理（幂等键冲突）返回既有受理标识，不产生第二条命令。
 * </p>
 */
@Service
public class FlowStartPortImpl implements FlowStartPort {

    private static final Logger log = LoggerFactory.getLogger(FlowStartPortImpl.class);

    private final BpmFormBindingService bindingService;
    private final BpmCommandQueue commandQueue;
    private final ObjectMapper objectMapper;
    private final com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService;
    private final com.sw.ck.bpm.process.service.LightProcessClassifier lightProcessClassifier;

    public FlowStartPortImpl(BpmFormBindingService bindingService,
                             BpmCommandQueue commandQueue,
                             ObjectMapper objectMapper,
                             com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService,
                             com.sw.ck.bpm.process.service.LightProcessClassifier lightProcessClassifier) {
        this.bindingService = bindingService;
        this.commandQueue = commandQueue;
        this.objectMapper = objectMapper;
        this.admissionService = admissionService;
        this.lightProcessClassifier = lightProcessClassifier;
    }

    @Override
    public Optional<Long> acceptFlowStart(FormSubmittedEvent event) {
        var bindings = bindingService.findActiveByFormKey(event.getFormKey());
        if (bindings.isEmpty()) {
            log.debug("表单 {} 无启用绑定，不受理流程发起", event.getFormKey());
            return Optional.empty();
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
        // 同身份先回查原结果（方向合同）：重放不重复占用额度、不重入队
        Optional<Long> replay = commandQueue.findByKey(event.getTenantId(), commandKey)
                .map(CommandEnvelope::getCommandId);
        if (replay.isPresent()) {
            return replay;
        }
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.FLOW_START);
        envelope.setChannel(resolveChannel(event.getDispatchChannel()));
        envelope.setCommandKey(commandKey);
        envelope.setTenantId(event.getTenantId());
        envelope.setInitiatorId(Long.valueOf(event.getSubmitter()));
        envelope.setPayload(toPayload(event, resolvedProcessDefKey));
        // 资源准入置于 enqueue 之后（RA02：断「持 usage 锁等命令行唯一索引」死锁环）；
        // 拒绝异常即整笔回滚（含命令行）。完成点受理冻结：轻流程=TARGET_ACTION_DONE；
        // 普通流程=FLOW_STARTED。
        boolean light = lightProcessClassifier.isLightProcess(event.getTenantId(), resolvedProcessDefKey);
        envelope.setCompletionPoint(light
                ? com.sw.ck.bpm.process.service.ResourceReleaseService.COMPLETION_POINT_TARGET_DONE
                : "FLOW_STARTED");
        try {
            Optional<Long> accepted = Optional.of(commandQueue.enqueue(envelope));
            com.sw.ck.bpm.process.service.ResourceAdmissionService.AdmissionTicket ticket =
                    admissionService.admit(event.getTenantId(),
                            com.sw.ck.bpm.process.entity.ResourceClassEnum.PROD, 1, commandKey);
            if (ticket != null) {
                envelope.setResourceClass(com.sw.ck.bpm.process.entity.ResourceClassEnum.PROD.getCode());
                envelope.setResourceUnits(1);
                envelope.setResourceSegment(ticket.segment());
                envelope.setPolicyVersion(ticket.policyVersion());
                commandQueue.updateResourceFreeze(envelope);
            }
            return accepted;
        } catch (DuplicateKeyException e) {
            // 幂等：同一提交意图的重复受理返回同一结果；并发未提交可见时无法回查受理标识，
            // 此时受理事实已由并发方持久化，本次按 no-op 返回（不得伪造标识）。
            return commandQueue.findByKey(event.getTenantId(), commandKey)
                    .map(envelope1 -> Optional.of(envelope1.getCommandId()))
                    .orElseGet(() -> {
                        log.warn("流程发起命令幂等键冲突但回查不可见: tenantId={}, commandKey={}",
                                event.getTenantId(), commandKey);
                        return Optional.empty();
                    });
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

    /**
     * 受控恢复（复审05 P1-06b）：为已受理但二段 FLOW_START 终态失败/过期的意图登记新的
     * 恢复代命令（key={@code FLOW_START:{recordId}:R{n}}），载荷沿用原受理输入（formKey/
     * recordId/submitter/submittedData 取自原载荷，输入冻结不重算），仅将流程指针
     * {@code processDefKey} 指向**当前有效绑定**。
     * <p>
     * 原始命令行与其冻结载荷一律保留（审计可回查），恢复只新增行：不篡改历史载荷、
     * 不重复目标记录（businessKey=recordId 不变，启动幂等仍由消费方按 businessKey 保证）。
     * 无有效绑定返回 empty = 明确安全处置（零目标），不冒称成功。
     * </p>
     *
     * @param tenantId        租户
     * @param originalPayload 原 FLOW_START 受理载荷（含 recordId/formKey/submitter/submittedData）
     * @return 新恢复代命令标识；empty=当前无有效绑定（安全处置，零目标）
     */
    public Optional<Long> recoverFlowStart(Long tenantId, Map<String, Object> originalPayload) {
        if (originalPayload == null) {
            return Optional.empty();
        }
        Object formKeyObj = originalPayload.get("formKey");
        Object recordIdObj = originalPayload.get("recordId");
        if (formKeyObj == null || String.valueOf(formKeyObj).isBlank()
                || recordIdObj == null || String.valueOf(recordIdObj).isBlank()) {
            return Optional.empty();
        }
        String formKey = String.valueOf(formKeyObj);
        String recordId = String.valueOf(recordIdObj);
        var bindings = bindingService.findActiveByFormKey(formKey);
        if (bindings.isEmpty()) {
            log.info("受控恢复跳过：表单无启用绑定（安全处置零目标）: formKey={}, recordId={}",
                    formKey, recordId);
            return Optional.empty();
        }
        if (bindings.size() != 1) {
            throw new IllegalStateException("表单存在多个有效流程绑定: formKey=" + formKey);
        }
        String resolvedProcessDefKey = bindings.get(0).getProcessDefKey();
        String baseKey = "FLOW_START:" + recordId;
        int generation = 1;
        String commandKey;
        while (true) {
            commandKey = baseKey + ":R" + generation;
            if (commandQueue.findByKey(tenantId, commandKey).isEmpty()) {
                break;
            }
            generation++;
        }
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.FLOW_START);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey(commandKey);
        envelope.setTenantId(tenantId);
        Object submitter = originalPayload.get("submitter");
        envelope.setInitiatorId(submitter == null ? null : Long.valueOf(String.valueOf(submitter)));
        Map<String, Object> payload = new LinkedHashMap<>(originalPayload);
        payload.put("processDefKey", resolvedProcessDefKey);
        envelope.setPayload(toPayload(payload, recordId));
        boolean light = lightProcessClassifier.isLightProcess(tenantId, resolvedProcessDefKey);
        envelope.setCompletionPoint(light
                ? com.sw.ck.bpm.process.service.ResourceReleaseService.COMPLETION_POINT_TARGET_DONE
                : "FLOW_STARTED");
        Optional<Long> accepted = Optional.of(commandQueue.enqueue(envelope));
        com.sw.ck.bpm.process.service.ResourceAdmissionService.AdmissionTicket ticket =
                admissionService.admit(tenantId,
                        com.sw.ck.bpm.process.entity.ResourceClassEnum.PROD, 1, commandKey);
        if (ticket != null) {
            envelope.setResourceClass(com.sw.ck.bpm.process.entity.ResourceClassEnum.PROD.getCode());
            envelope.setResourceUnits(1);
            envelope.setResourceSegment(ticket.segment());
            envelope.setPolicyVersion(ticket.policyVersion());
            commandQueue.updateResourceFreeze(envelope);
        }
        log.warn("二段流程发起受控恢复已受理: recordId={}, recoveryKey={}, processDefKey={}",
                recordId, commandKey, resolvedProcessDefKey);
        return accepted;
    }

    private String toPayload(Map<String, Object> payload, String recordId) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化流程发起恢复 payload 失败: recordId=" + recordId, e);
        }
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
