package com.sw.ck.bpm.process.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.BpmDraft;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.entity.DraftStatusEnum;
import com.sw.ck.bpm.process.service.BpmDraftService;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 草稿提交命令处理器。
 * <p>
 * 消费 DRAFT_SUBMIT 受理：经 {@link FormDataSubmitFacade} 以幂等键落表单数据
 *（同一草稿提交序号不重复落数据），表单提交事务内自动受理 FLOW_START，
 * 随后流程经 FlowStartCommandHandler 发起。成功后草稿转 SUBMITTED 并记录
 * recordId；失败按有界重试，终态失败时草稿转 FAILED 可修正后重新提交。
 * </p>
 */
@Component
public class DraftSubmitCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(DraftSubmitCommandHandler.class);

    private final BpmDraftService draftService;
    private final FormDataSubmitFacade formDataSubmitFacade;
    private final ObjectMapper objectMapper;

    public DraftSubmitCommandHandler(BpmDraftService draftService,
                                     FormDataSubmitFacade formDataSubmitFacade,
                                     ObjectMapper objectMapper) {
        this.draftService = draftService;
        this.formDataSubmitFacade = formDataSubmitFacade;
        this.objectMapper = objectMapper;
    }

    @Override
    public java.util.Set<CommandTypeEnum> types() {
        return java.util.Set.of(CommandTypeEnum.DRAFT_SUBMIT);
    }

    @Override
    public String handle(CommandEnvelope envelope) throws Exception {
        // 命令键第二段为 draftId（DRAFT_SUBMIT:<draftId>:<submitSeq>）；
        // PG 侧 bigint 列不接受 varchar 比较（H2 宽松语义掩盖），显式解析为 Long
        BpmDraft draft = draftService.getById(Long.valueOf(envelope.getCommandKey().split(":")[1]));
        if (draft == null) {
            throw new IllegalStateException("草稿不存在: " + envelope.getCommandKey());
        }
        if (DraftStatusEnum.SUBMITTED.getCode().equals(draft.getStatus())) {
            log.info("草稿提交命令幂等跳过: draftId={} 已提交", draft.getId());
            return "{\"status\":\"SKIP_SUBMITTED\",\"recordId\":\"" + draft.getResultRecordId() + "\"}";
        }

        Map<String, Object> payload = objectMapper.readValue(
                envelope.getPayload() == null ? "{}" : envelope.getPayload(), Map.class);
        String formKey = (String) payload.get("formKey");
        int submitSeq = payload.get("submitSeq") != null
                ? (Integer) payload.get("submitSeq") : draft.getSubmitSeq();
        String idempotencyKey = "DRAFT_SUBMIT:" + draft.getId() + ":" + submitSeq;

        Map<String, Object> submittedData = new LinkedHashMap<>();
        Object data = payload.get("submittedData");
        if (data instanceof Map<?, ?> map) {
            map.forEach((k, v) -> submittedData.put(String.valueOf(k), v));
        }

        String dispatchChannel = envelope.getChannel() == null ? null : envelope.getChannel().getCode();
        String processDefKey = (String) payload.get("processDefKey");
        // 没有流程快照时走旧四参契约，保持中台 Facade 的二进制/测试兼容；
        // 正式草稿提交命令始终携带服务端解析出的快照并走五参扩展契约。
        // submit 当前契约恒 present（提交失败抛业务异常），为空属契约破坏即显式失败。
        String recordId = (processDefKey == null || processDefKey.isBlank()
                ? formDataSubmitFacade.submit(formKey, submittedData, idempotencyKey, dispatchChannel)
                : formDataSubmitFacade.submit(formKey, submittedData, idempotencyKey, dispatchChannel, processDefKey))
                .orElseThrow(() -> new IllegalStateException(
                        "表单提交未返回记录标识: formKey=" + formKey + ", idempotencyKey=" + idempotencyKey));

        draft.setStatus(DraftStatusEnum.SUBMITTED.getCode());
        draft.setResultRecordId(recordId);
        draft.setLastError(null);
        draftService.updateById(draft);
        log.info("草稿已提交: draftId={}, recordId={}", draft.getId(), recordId);
        return "{\"status\":\"SUBMITTED\",\"recordId\":\"" + recordId + "\"}";
    }

    @Override
    public void onFinalFailure(CommandEnvelope envelope, String reason) {
        // 有界重试耗尽：草稿转 FAILED，可修正后重新提交（D3：失败保留内容）
        String draftId = envelope.getCommandKey().split(":")[1];
        BpmDraft draft = draftService.getById(Long.valueOf(envelope.getCommandKey().split(":")[1]));
        if (draft == null) {
            return;
        }
        draft.setStatus(DraftStatusEnum.FAILED.getCode());
        draft.setLastError(reason != null && reason.length() > 1000 ? reason.substring(0, 997) + "..." : reason);
        draftService.updateById(draft);
        log.warn("草稿提交终态失败: draftId={}, reason={}", draft.getId(), reason);
    }
}
