package com.sw.ck.bpm.process.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
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
 * </p>
 */
@Component
public class FlowStartCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(FlowStartCommandHandler.class);

    private final ProcessStartService processStartService;
    private final BpmInstanceService bpmInstanceService;
    private final ObjectMapper objectMapper;

    public FlowStartCommandHandler(ProcessStartService processStartService,
                                   BpmInstanceService bpmInstanceService,
                                   ObjectMapper objectMapper) {
        this.processStartService = processStartService;
        this.bpmInstanceService = bpmInstanceService;
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
        return "{\"status\":\"STARTED\"}";
    }
}
