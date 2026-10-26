package com.sw.ck.bpm.process.queue;

import com.alibaba.fastjson2.JSON;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmActionRef;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.NodeFormDataService;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * P64 配置化动作命令处理器：消费 ORCH_ACTION_START 受理（ADR-P64-001 §4，A04）。
 * <p>
 * 单事务：目标表单记录经 {@link FormDataSubmitFacade#submit}（幂等键=commandKey 的稳定摘要）创建，
 * 目标流程实例沿既有 FLOW_START 可靠链在同事务内受理发起；重放回查原结果；
 * 恢复后只处理未完成意图，不重复启动。
 * </p>
 * <p>
 * 记录归属接缝（复审05 P2-04a，A06）：目标定义首节点参与人策略为 FORM_FIELD/USER/MAIN
 * 时，以映射载荷中的该字段值（被分配办理人用户 ID）作为目标记录归属人落库
 * （create_by/update_by），使既有数据范围模型（SELF→create_by 等值）对系统派发给普通
 * 办理人的子记录自然生效；解析不可得（无 FORM_FIELD 参与人/字段缺失/非法值）时保持
 * 原行为（create_by=命令发起人），不改变无该配置流程的任何语义。派发审计链
 * （sw_bpm_command/sw_bpm_action_ref/sw_bpm_child_batch）不受影响。
 * </p>
 */
@Component
public class OrchActionStartCommandHandler implements BpmCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(OrchActionStartCommandHandler.class);

    private final BpmActionRefMapper actionRefMapper;
    private final FormDataSubmitFacade formDataSubmitFacade;
    private final BpmProcessDefService processDefService;
    private final NodeFormDataService nodeFormDataService;

    public OrchActionStartCommandHandler(BpmActionRefMapper actionRefMapper,
                                         FormDataSubmitFacade formDataSubmitFacade,
                                         BpmProcessDefService processDefService,
                                         NodeFormDataService nodeFormDataService) {
        this.actionRefMapper = actionRefMapper;
        this.formDataSubmitFacade = formDataSubmitFacade;
        this.processDefService = processDefService;
        this.nodeFormDataService = nodeFormDataService;
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
        // 幂等：已成功/启动中意图回查原结果，重复投递/恢复后不重复启动
        if (("STARTED".equals(ref.getStatus()) || "STARTING".equals(ref.getStatus()))
                && ref.getTargetRecordId() != null) {
            log.info("动作意图幂等跳过: commandKey={}, recordId={} 已受理启动",
                    envelope.getCommandKey(), ref.getTargetRecordId());
            return "{\"status\":\"SKIP_DUPLICATE\",\"recordId\":\"" + ref.getTargetRecordId() + "\"}";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = payload.get("data") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        String targetFormKey = ref.getTargetFormKey() == null
                ? String.valueOf(payload.get("targetFormKey")) : ref.getTargetFormKey();
        // 同事务：建目标记录（幂等键=commandKey 的稳定摘要）→ FlowStartPort 同事务受理 FLOW_START
        // commandKey 含 execKey/动作/项，实测可超过表单 trace 的 128 字符列；摘要保持唯一且定长。
        String idempotencyKey = formIdempotencyKey(envelope.getCommandKey());
        Long recordOwnerUserId = resolveRecordOwnerUserId(ref.getTargetDefKey(), data);
        // 系统派发无登录用户上下文：目标记录归属/目标流程发起人回落到源实例发起人，
        // 避免调度线程的系统用户被当作目标流程发起人（跨租户/停用校验会拒绝）。
        Long targetOwnerUserId = recordOwnerUserId != null
                ? recordOwnerUserId : envelope.getInitiatorId();
        String recordId = (targetOwnerUserId != null
                ? formDataSubmitFacade.submitOnBehalfOf(targetFormKey, data,
                        idempotencyKey, targetOwnerUserId)
                : formDataSubmitFacade.submit(targetFormKey, data, idempotencyKey))
                .orElseThrow(() -> new IllegalStateException("目标表单记录创建未返回标识: "
                        + targetFormKey));
        ref.setTargetRecordId(recordId);
        // 状态语义（审查02 P1-06b）：STARTING=目标记录已建且 FLOW_START 已受理，目标实例
        // 尚未由既有消费链异步创建完成；"STARTED"由回查层按目标实例持久事实解析展示，
        // 不在受理事务内冒称目标已启动。
        ref.setStatus("STARTING");
        actionRefMapper.updateById(ref);
        log.info("关联实例动作已启动: commandKey={}, targetFormKey={}, recordId={}, recordOwner={}",
                envelope.getCommandKey(), targetFormKey, recordId, targetOwnerUserId);
        return "{\"status\":\"STARTED\",\"recordId\":\"" + recordId + "\"}";
    }

    /**
     * 表单提交幂等键：commandKey 的 SHA-256 摘要（68 字符），稳定、唯一且不超
     * {@code sw_form_trace.submit_idempotency_key} 的 128 字符上限。
     */
    static String formIdempotencyKey(String commandKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(commandKey).getBytes(StandardCharsets.UTF_8));
            return "P64:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 从目标定义已发布版本图解析首节点 FORM_FIELD/USER/MAIN 参与人字段值（=记录归属人）。
     * 解析条件不满足或异常时返回 null（保持原 create_by 语义，不阻断派发）。
     */
    private Long resolveRecordOwnerUserId(String targetDefKey, Map<String, Object> data) {
        try {
            if (targetDefKey == null || targetDefKey.isBlank() || data.isEmpty()) {
                return null;
            }
            BpmProcessDef def = processDefService.findByProcessKey(targetDefKey);
            if (def == null || def.getPublishedVersion() == null) {
                return null;
            }
            ProcessGraph graph = nodeFormDataService.loadGraph(targetDefKey, def.getPublishedVersion());
            if (graph == null || graph.getElements() == null) {
                return null;
            }
            String firstNodeId = firstNodeAfterStart(graph);
            if (firstNodeId == null) {
                return null;
            }
            return graph.getElements().stream()
                    .filter(element -> "node".equals(element.getKind()) && firstNodeId.equals(element.getId()))
                    .findFirst()
                    .flatMap(element -> participantOwnerUserId(
                            element.getConfig() == null ? null : element.getConfig().get("participant"), data))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("记录归属人解析跳过（保持命令发起人语义）: targetDefKey={}, error={}",
                    targetDefKey, e.getMessage());
            return null;
        }
    }

    /** 起始节点的第一条出边目标（与引擎首个进入节点一致）。 */
    private String firstNodeAfterStart(ProcessGraph graph) {
        String startId = graph.getElements().stream()
                .filter(element -> "node".equals(element.getKind())
                        && "START".equalsIgnoreCase(element.getType()))
                .map(com.sw.ck.bpm.api.dto.GraphElement::getId)
                .findFirst().orElse(null);
        if (startId == null) {
            return null;
        }
        return graph.getElements().stream()
                .filter(element -> "edge".equals(element.getKind()) && startId.equals(element.getSource()))
                .map(com.sw.ck.bpm.api.dto.GraphElement::getTarget)
                .filter(target -> target != null && !target.isBlank())
                .findFirst().orElse(null);
    }

    /** FORM_FIELD/USER/MAIN 参与人绑定 → 载荷字段中的正数值用户 ID（否则 empty）。 */
    private java.util.Optional<Long> participantOwnerUserId(Object participant, Map<String, Object> data) {
        if (!(participant instanceof Map<?, ?> binding)) {
            return java.util.Optional.empty();
        }
        if (!"FORM_FIELD".equalsIgnoreCase(text(binding.get("strategy")))) {
            return java.util.Optional.empty();
        }
        if (!(binding.get("value") instanceof Map<?, ?> value)) {
            return java.util.Optional.empty();
        }
        if (!"USER".equalsIgnoreCase(text(value.get("objectType")))
                || !"MAIN".equalsIgnoreCase(text(value.get("scope")))) {
            return java.util.Optional.empty();
        }
        String field = text(value.get("field"));
        if (field == null) {
            return java.util.Optional.empty();
        }
        Object raw = data.get(field);
        if (raw == null) {
            return java.util.Optional.empty();
        }
        String numeric = String.valueOf(raw).trim();
        if (!numeric.matches("\\d+")) {
            return java.util.Optional.empty();
        }
        long userId = Long.parseLong(numeric);
        return userId > 0 ? java.util.Optional.of(userId) : java.util.Optional.empty();
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
