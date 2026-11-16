package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.vo.PendingSignVO;
import com.sw.ck.bpm.api.common.BatchItemStatusEnum;
import com.sw.ck.bpm.process.entity.BpmAuthorizeRule;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmSignRecord;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.mapper.BpmSignRecordMapper;
import com.sw.ck.bpm.process.service.ApprovalLifecycleService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.sw.ck.bpm.process.entity.BpmSignPositionEnum;
import com.sw.ck.bpm.process.vo.BpmAuthorizeRuleVO;
/**
 * I3 人工审批生命周期端点：转办/委托/代理/撤回/沟通/废弃/加签/补签。
 * <p>
 * 服务端 @PreAuthorize 是唯一权威；任务/实例归属校验在
 * {@link ApprovalLifecycleService} 内统一执行，页面隐藏不替代授权。
 * </p>
 */
@RestController
@RequestMapping("/workflow")
@Log4j2
public class BpmLifecycleController {

    @Autowired
    private ApprovalLifecycleService lifecycleService;
    @Autowired
    private BpmSignRecordMapper signRecordMapper;
    @Autowired
    private BpmInstanceService bpmInstanceService;
    @Autowired
    private BpmTaskFacade bpmTaskFacade;

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:transfer')")
    @PostMapping("/tasks/{taskId}/transfer")
    public R<Void> transfer(@PathVariable String taskId,
                            @RequestBody ApprovalActionRequest request) {
        request.setAction(ApprovalAction.TRANSFER);
        return lifecycleService.executeTaskAction(taskId, request);
    }

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:delegate')")
    @PostMapping("/tasks/{taskId}/delegate")
    public R<Void> delegate(@PathVariable String taskId,
                            @RequestBody ApprovalActionRequest request) {
        request.setAction(ApprovalAction.DELEGATE);
        return lifecycleService.executeTaskAction(taskId, request);
    }

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:communicate')")
    @PostMapping("/tasks/{taskId}/communicate")
    public R<Void> communicate(@PathVariable String taskId,
                               @RequestBody ApprovalActionRequest request) {
        request.setAction(ApprovalAction.COMMUNICATE);
        return lifecycleService.executeTaskAction(taskId, request);
    }

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:add-sign')")
    @PostMapping("/tasks/{taskId}/add-sign")
    public R<Void> addSign(@PathVariable String taskId,
                           @RequestBody ApprovalActionRequest request) {
        request.setAction(ApprovalAction.ADD_SIGN);
        return lifecycleService.executeTaskAction(taskId, request);
    }

    /** 补签：任务已越过节点后按原实例/原节点/原终态补充确认。 */
    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:add-sign')")
    @PostMapping("/instances/{instanceId}/supplement-sign")
    public R<Void> supplementSign(@PathVariable String instanceId,
                                  @RequestBody ApprovalActionRequest request) {
        request.setAction(ApprovalAction.SUPPLEMENT_SIGN);
        LoginUser actor = LoginUserHolder.get();
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(instanceId)
                .orElseThrow(() -> new BaseException(
                        CommonErrorCode.NOT_FOUND.getCode(), "实例不存在"));
        if (instance.getStatus() == null || InstanceStatusEnum.RUNNING.getCode().equalsIgnoreCase(instance.getStatus())) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(),
                    "补签仅适用于已越过终态的实例");
        }
        // 越权门（二级提示 G9b）：仅发起人或系统管理员可对已终结实例发起补签；
        // 其他主体（含持同权限按钮的业务角色）明确拒绝，零副作用。
        boolean initiator = instance.getInitiatorId() != null
                && instance.getInitiatorId().equals(actor.getUserId());
        if (!actor.isSuperAdmin() && !initiator) {
            throw new BaseException(BpmErrorCode.INVALIDATE_NOT_PERMITTED.getCode(),
                    "仅发起人或系统管理员可对该实例补签");
        }
        List<Long> participants = request.getParticipants();
        if (participants == null || participants.isEmpty()) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID);
        }
        for (Long participant : participants) {
            if (participant.equals(actor.getUserId())) {
                throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(), "不能向本人补签");
            }
            // 幂等门：同实例同参与人已存在待处理补签 → 拒绝重复（二级提示 G9b）
            long duplicate = signRecordMapper.selectCount(
                    Wrappers.<BpmSignRecord>lambdaQuery()
                            .eq(BpmSignRecord::getProcessInstanceId, instanceId)
                            .eq(BpmSignRecord::getSignType, "SUPPLEMENT_SIGN")
                            .eq(BpmSignRecord::getParticipantId, participant)
                            .eq(BpmSignRecord::getSignStatus, BatchItemStatusEnum.PENDING.getCode()));
            if (duplicate > 0) {
                throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(),
                        "该参与人已有待处理补签，不得重复补签");
            }
            BpmSignRecord record = new BpmSignRecord();
            record.setProcessInstanceId(instanceId);
            record.setNodeKey(request.getNodeKey() == null ? "INSTANCE" : request.getNodeKey());
            record.setTaskId("SUPPLEMENT#" + instanceId);
            record.setSignType("SUPPLEMENT_SIGN");
            record.setModeType("PARALLEL");
            record.setOperatorId(actor.getUserId());
            record.setParticipantId(participant);
            record.setSeqNo(0);
            record.setSignStatus(BatchItemStatusEnum.PENDING.getCode());
            record.setOriginalTaskId(request.getReturnTargetNodeId());
            record.setOriginalStatus(instance.getStatus());
            record.setDetail(toJsonString(mapOf("auditOnly", true,
                    "businessAction", request.getMessage() == null ? "" : request.getMessage())));
            record.setTenantId(actor.getTenantId());
            signRecordMapper.insert(record);
        }
        log.info("补签已登记: instanceId={}, participants={}", instanceId, participants);
        return R.ok();
    }

    private String toJsonString(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private String extractDetail(String json, String key, String fallback) {
        try {
            JsonNode node = new ObjectMapper()
                    .readTree(json == null ? "{}" : json);
            return node.path(key).asText(fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/sign/pending")
    public R<List<PendingSignVO>> pendingSigns() {
        LoginUser actor = requireAuthenticatedActor();
        List<BpmSignRecord> records = signRecordMapper.selectList(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .eq(BpmSignRecord::getParticipantId, actor.getUserId())
                        .eq(BpmSignRecord::getTenantId, actor.getTenantId())
                        .eq(BpmSignRecord::getSignStatus, BatchItemStatusEnum.PENDING.getCode())
                        .orderByAsc(BpmSignRecord::getCreateTime)
                        .orderByAsc(BpmSignRecord::getSeqNo));
        List<String> taskIds = records.stream().map(BpmSignRecord::getTaskId).distinct().toList();
        List<BpmSignRecord> allPendingForTasks = taskIds.isEmpty() ? List.of() : signRecordMapper.selectList(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .in(BpmSignRecord::getTaskId, taskIds)
                        .eq(BpmSignRecord::getTenantId, actor.getTenantId())
                        .eq(BpmSignRecord::getSignStatus, BatchItemStatusEnum.PENDING.getCode()));
        List<PendingSignVO> out = new ArrayList<>();
        for (BpmSignRecord record : records) {
            boolean serialBlocked = "SERIAL".equalsIgnoreCase(record.getModeType())
                    && allPendingForTasks.stream().anyMatch(other -> Objects.equals(record.getTaskId(), other.getTaskId())
                    && other.getSeqNo() != null && record.getSeqNo() != null
                    && other.getSeqNo() < record.getSeqNo());
            PendingSignVO item = new PendingSignVO();
            item.setRecordId(record.getId());
            item.setTaskId(record.getTaskId());
            item.setProcessInstanceId(record.getProcessInstanceId());
            item.setNodeKey(record.getNodeKey());
            item.setSignType(record.getSignType());
            item.setModeType(record.getModeType());
            item.setSeqNo(record.getSeqNo());
            item.setSignPosition(extractDetail(record.getDetail(), "signPosition", BpmSignPositionEnum.BEFORE.getCode()));
            item.setComment(extractDetail(record.getDetail(), "comment", ""));
            item.setSerialBlocked(serialBlocked);
            if (record.getTaskId() != null && !record.getTaskId().startsWith("SUPPLEMENT#")) {
                bpmTaskFacade.getTask(record.getTaskId()).ifPresent(task -> {
                    item.setTaskName(task.getName());
                    item.setProcessDefinitionKey(task.getProcessDefinitionKey());
                });
            }
            out.add(item);
        }
        return R.ok(out);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/sign/{recordId}/express")
    public R<Void> expressSign(@PathVariable Long recordId,
                               @RequestBody ApprovalActionRequest request) {
        if (request.getAction() != ApprovalAction.APPROVE
                && request.getAction() != ApprovalAction.DISAPPROVE) {
            request.setAction(ApprovalAction.APPROVE);
        }
        return lifecycleService.expressSign(recordId, request);
    }

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:add-sign')")
    @PostMapping("/sign/{recordId}/cancel")
    public R<Void> cancelSign(@PathVariable Long recordId,
                              @RequestBody ApprovalActionRequest request) {
        return lifecycleService.cancelSign(recordId, request);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/communications/{communicationId}/reply")
    public R<Void> replyCommunication(@PathVariable Long communicationId,
                                      @RequestBody ApprovalActionRequest request) {
        return lifecycleService.replyCommunication(communicationId, request);
    }

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:withdraw')")
    @PostMapping("/my/instances/{instanceId}/withdraw")
    public R<Void> withdraw(@PathVariable String instanceId,
                            @RequestBody(required = false) ApprovalActionRequest request) {
        return lifecycleService.withdraw(instanceId,
                request == null ? new ApprovalActionRequest() : request);
    }

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:discard')")
    @PostMapping("/instances/{instanceId}/discard")
    public R<Void> discard(@PathVariable String instanceId,
                           @RequestBody(required = false) ApprovalActionRequest request) {
        return lifecycleService.discard(instanceId,
                request == null ? new ApprovalActionRequest() : request);
    }

    // ==================== 授权代理规则 ====================

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:def:design') or @ss.hasPermi('workflow:task:authorize')")
    @PostMapping("/authorize-rules")
    public R<Long> saveAuthorizeRule(@RequestBody ApprovalActionRequest request) {
        return lifecycleService.saveAuthorizeRule(request);
    }

    @Transactional
    @PreAuthorize("@ss.hasPermi('workflow:task:authorize')")
    @DeleteMapping("/authorize-rules/{ruleId}")
    public R<Void> revokeAuthorizeRule(@PathVariable Long ruleId) {
        return lifecycleService.revokeAuthorizeRule(ruleId, new ApprovalActionRequest());
    }

    @PreAuthorize("@ss.hasPermi('workflow:task:authorize')")
    @GetMapping("/authorize-rules")
    public R<List<BpmAuthorizeRuleVO>> listAuthorizeRules() {
        return R.ok(lifecycleService.listAuthorizeRules() == null ? List.of() : lifecycleService.listAuthorizeRules().stream().map(BpmAuthorizeRuleVO::from).toList());
    }

    private LoginUser requireAuthenticatedActor() {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null || actor.getUserId() == null || actor.getTenantId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        return actor;
    }
}
