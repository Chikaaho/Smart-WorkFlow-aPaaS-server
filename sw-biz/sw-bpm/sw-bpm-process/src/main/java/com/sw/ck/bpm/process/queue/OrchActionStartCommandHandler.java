package com.sw.ck.bpm.process.queue;

import com.alibaba.fastjson2.JSON;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * P64 配置化动作命令处理器：消费 ORCH_ACTION_START 受理（ADR-P64-001 §4，A04）。
 * <p>
 * 单事务：目标表单记录经 {@link FormDataSubmitFacade#submit}（幂等键=commandKey）创建，
 * 目标流程实例沿既有 FLOW_START 可靠链在同事务内受理发起；重放回查原结果；
 * 恢复后只处理未完成意图，不重复启动。
 * </p>
 */
@Component
public class OrchActionStartCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(OrchActionStartCommandHandler.class);

    private final BpmActionRefMapper actionRefMapper;
    private final FormDataSubmitFacade formDataSubmitFacade;

    public OrchActionStartCommandHandler(BpmActionRefMapper actionRefMapper,
                                         FormDataSubmitFacade formDataSubmitFacade) {
        this.actionRefMapper = actionRefMapper;
        this.formDataSubmitFacade = formDataSubmitFacade;
    }

    @Override
    public java.util.Set<CommandTypeEnum> types() {
        return java.util.Set.of(CommandTypeEnum.ORCH_ACTION_START);
    }

    @Override
    @Transactional
    public String handle(CommandEnvelope envelope) throws Exception {
        Map<String, Object> payload = envelope.getPayload() == null || envelope.getPayload().isBlank()
                ? Map.of() : JSON.parseObject(envelope.getPayload());
        long refId = payload.get("refId") == null ? -1L
                : Long.parseLong(String.valueOf(payload.get("refId")));
        BpmActionRef ref = refId > 0 ? actionRefMapper.selectById(refId) : null;
        if (ref == null) {
            // 意图行缺失 = 源事务未提交完整事实，按失败处理（不冒称成功）
            throw new IllegalStateException("动作意图行缺失: refId=" + refId
                    + ", commandKey=" + envelope.getCommandKey());
        }
        // 幂等：已成功意图回查原结果，重复投递/恢复后不重复启动
        if ("STARTED".equals(ref.getStatus()) && ref.getTargetRecordId() != null) {
            log.info("动作意图幂等跳过: commandKey={}, recordId={} 已启动",
                    envelope.getCommandKey(), ref.getTargetRecordId());
            return "{\"status\":\"SKIP_DUPLICATE\",\"recordId\":\"" + ref.getTargetRecordId() + "\"}";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = payload.get("data") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        String targetFormKey = ref.getTargetFormKey() == null
                ? String.valueOf(payload.get("targetFormKey")) : ref.getTargetFormKey();
        // 同事务：建目标记录（幂等键=commandKey）→ FlowStartPort 同事务受理 FLOW_START
        String recordId = formDataSubmitFacade.submit(targetFormKey, data, envelope.getCommandKey())
                .orElseThrow(() -> new IllegalStateException("目标表单记录创建未返回标识: "
                        + targetFormKey));
        ref.setTargetRecordId(recordId);
        ref.setStatus("STARTED");
        actionRefMapper.updateById(ref);
        log.info("关联实例动作已启动: commandKey={}, targetFormKey={}, recordId={}",
                envelope.getCommandKey(), targetFormKey, recordId);
        return "{\"status\":\"STARTED\",\"recordId\":\"" + recordId + "\"}";
    }
}
