package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.api.common.BatchItemStatusEnum;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmSignRecord;
import com.sw.ck.bpm.process.entity.BpmSignPositionEnum;
import com.sw.ck.bpm.process.mapper.BpmSignRecordMapper;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 加签 / 补签表态与取消执行（自 ApprovalLifecycleServiceImpl 拆出，行为不变）：
 * 表态资格（本人 + 租户 + 状态门）、串行加签顺序门、乐观锁冲突拒绝、表决结算
 * （AUTO_FINISH 受控完成原任务）。事务边界保留在
 * {@link ApprovalLifecycleServiceImpl} 门面的公开方法上。
 */
@Service
@Log4j2
class ApprovalSignActionExecutor {

    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private BpmSignRecordMapper signRecordMapper;
    @Autowired
    private ObjectProvider<TaskActionService> taskActionService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApprovalLifecycleSupport support;

    void expressSign(Long recordId, ApprovalActionRequest request) {
        LoginUser actor = support.requireAuthenticatedActor();
        BpmSignRecord record = signRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BaseException(BpmErrorCode.SIGN_RECORD_NOT_FOUND);
        }
        support.requireSameTenant(record.getTenantId(), actor, "加签/补签记录不属于当前租户");
        if (!Objects.equals(record.getParticipantId(), actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅追加人本人可表态");
        }
        if ("DONE".equals(record.getSignStatus())) {
            return; // 幂等
        }
        if (!BatchItemStatusEnum.PENDING.getCode().equals(record.getSignStatus())) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
        if (ApprovalLifecycleSupport.MODE_SERIAL.equals(record.getModeType())
                && ApprovalLifecycleSupport.SIGN_TYPE_ADD.equals(record.getSignType())) {
            if (record.getSeqNo() == null) {
                throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(),
                        "串行加签记录缺少顺序号");
            }
            long before = signRecordMapper.selectCount(
                    Wrappers.<BpmSignRecord>lambdaQuery()
                            .eq(BpmSignRecord::getTaskId, record.getTaskId())
                            .eq(BpmSignRecord::getTenantId, actor.getTenantId())
                            .eq(BpmSignRecord::getSignType, ApprovalLifecycleSupport.SIGN_TYPE_ADD)
                            .lt(BpmSignRecord::getSeqNo, record.getSeqNo())
                            .eq(BpmSignRecord::getSignStatus, BatchItemStatusEnum.PENDING.getCode()));
            if (before > 0) {
                throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(),
                        "串行加签存在前置未表态记录");
            }
        }
        String result = request.getAction() == ApprovalAction.DISAPPROVE
                ? "DISAPPROVE" : "APPROVE";
        BpmSignRecord patch = new BpmSignRecord();
        patch.setId(record.getId());
        patch.setVersion(record.getVersion());
        patch.setSignStatus("DONE");
        patch.setResultStatus(result);
        patch.setDetail(support.toJson(support.mergeDetails(record.getDetail(), support.mapOf(
                "result", result, "opinionData", request.getOpinionData(),
                "actor", actor.getUserId()))));
        int updated = signRecordMapper.updateById(patch);
        requireSignRecordTransition(updated, "加签/补签记录状态已变化，请刷新后重试");

        // 补签记录的 taskId 是 "SUPPLEMENT#<pid>"，不是 Flowable 任务——用签名记录自身的
        // 实例/节点/记录 ID 构造生命周期任务信息，保证补签表态同样落 ApprovalActionRecord
        // 与意见表单快照（Z5/Z8：SUPPLEMENT_SIGN 行不得缺快照）。
        // empty = 该任务不存在（原 null 返回路径）：补签任务非 Flowable 任务，回落自造生命周期任务信息
        BpmTaskVO lifecycleTask = bpmTaskFacade.getTask(record.getTaskId()).orElseGet(() -> {
            BpmTaskVO fallback = new BpmTaskVO();
            fallback.setTaskId(record.getTaskId());
            fallback.setProcessInstanceId(record.getProcessInstanceId());
            fallback.setTaskDefinitionKey(support.nullSafe(record.getNodeKey(), "SUPPLEMENT"));
            return fallback;
        });
        support.recordLifecycle(lifecycleTask, actor,
                ApprovalLifecycleSupport.SIGN_TYPE_ADD.equals(record.getSignType())
                        ? ApprovalAction.ADD_SIGN : ApprovalAction.SUPPLEMENT_SIGN,
                "SIGN_" + result, request, support.mapOf("recordId", record.getId()));

        if (ApprovalLifecycleSupport.SIGN_TYPE_ADD.equals(record.getSignType())) {
            trySettleOriginalTask(record);
        }
    }

    void cancelSign(Long recordId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmSignRecord record = signRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BaseException(BpmErrorCode.SIGN_RECORD_NOT_FOUND);
        }
        boolean operator = record.getOperatorId().equals(actor.getUserId());
        boolean participant = record.getParticipantId().equals(actor.getUserId());
        if (!operator && !participant) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(),
                    "仅操作人/本人可取消待处理加签");
        }
        if (!BatchItemStatusEnum.PENDING.getCode().equals(record.getSignStatus())) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
        BpmSignRecord patch = new BpmSignRecord();
        patch.setId(record.getId());
        patch.setVersion(record.getVersion());
        patch.setSignStatus(BpmChildBatch.STATUS_CANCELLED);
        patch.setCancelReason(support.nullSafe(request.getReason(), "操作人取消"));
        int updated = signRecordMapper.updateById(patch);
        requireSignRecordTransition(updated, "加签记录状态已变化，请刷新后重试");
        // empty = 该任务不存在（原 null 返回路径）：lifecycleTask 为空时不记录生命周期，语义等同
        bpmTaskFacade.getTask(record.getTaskId()).ifPresent(lifecycleTask -> support.recordLifecycle(
                lifecycleTask, actor,
                ApprovalLifecycleSupport.SIGN_TYPE_ADD.equals(record.getSignType())
                        ? ApprovalAction.ADD_SIGN : ApprovalAction.SUPPLEMENT_SIGN,
                "SIGN_CANCELLED", request, support.mapOf("recordId", record.getId())));
    }

    void assertNoPendingSign(String taskId) {
        long pending = signRecordMapper.selectCount(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .eq(BpmSignRecord::getTaskId, taskId)
                        .eq(BpmSignRecord::getSignType, ApprovalLifecycleSupport.SIGN_TYPE_ADD)
                        .eq(BpmSignRecord::getSignStatus, BatchItemStatusEnum.PENDING.getCode()));
        if (pending > 0) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(),
                    "存在待处理加签，不能直接办理");
        }
    }

    /** 加签全部表态完成后按策略结算原任务（AUTO_FINISH 直接受控完成）。 */
    private void trySettleOriginalTask(BpmSignRecord settled) {
        List<BpmSignRecord> all = signRecordMapper.selectList(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .eq(BpmSignRecord::getTaskId, settled.getTaskId())
                        .eq(BpmSignRecord::getTenantId, settled.getTenantId())
                        .ne(BpmSignRecord::getSignStatus, BpmChildBatch.STATUS_CANCELLED));
        if (all.isEmpty()) {
            log.warn("加签结算跳过：任务无有效加签记录, taskId={}", settled.getTaskId());
            return;
        }
        boolean allDone = all.stream().allMatch(item -> "DONE".equals(item.getSignStatus()));
        if (!allDone) {
            return;
        }
        String policy = support.extractDetailField(all.get(0).getDetail(), "policy");
        boolean negative;
        if ("ALL_PASS".equals(policy)) {
            negative = all.stream().anyMatch(item -> "DISAPPROVE".equals(item.getResultStatus()));
        } else {
            negative = all.stream().noneMatch(item -> "APPROVE".equals(item.getResultStatus()));
        }
        // empty = 该任务不存在（原 null 返回路径）：原任务已消失，跳过结算
        Optional<BpmTaskVO> taskLookup = bpmTaskFacade.getTask(settled.getTaskId());
        if (taskLookup.isEmpty()) {
            log.warn("原任务已消失，跳过加签结算: taskId={}", settled.getTaskId());
            return;
        }
        BpmTaskVO task = taskLookup.get();
        String signPosition = support.extractDetailField(all.get(0).getDetail(), "signPosition");
        boolean autoFinish = "AUTO_FINISH".equals(policy) || BpmSignPositionEnum.AFTER.getCode().equalsIgnoreCase(signPosition);
        if (!autoFinish) {
            log.info("加签表决完成（原待办保留，由原责任人办理）: taskId={}, negative={}",
                    settled.getTaskId(), negative);
            return;
        }
        LoginUser synthetic = new LoginUser();
        synthetic.setUserId(all.get(0).getOperatorId());
        synthetic.setTenantId(all.get(0).getTenantId() == null ? 0L : all.get(0).getTenantId());
        LoginUserHolder.set(synthetic);
        try {
            ApprovalActionRequest settleRequest = new ApprovalActionRequest();
            settleRequest.setAction(negative ? ApprovalAction.DISAPPROVE : ApprovalAction.APPROVE);
            settleRequest.setComment(support.extractDetailField(all.get(0).getDetail(), "originalComment"));
            settleRequest.setOpinionFormId(support.extractDetailField(all.get(0).getDetail(), "opinionFormId"));
            settleRequest.setOpinionFormVersion(support.extractDetailField(all.get(0).getDetail(), "opinionFormVersion"));
            Map<String, Object> opinion = new LinkedHashMap<>(support.mapOf("source", ApprovalLifecycleSupport.SIGN_TYPE_ADD));
            String originalOpinion = support.extractDetailField(all.get(0).getDetail(), "originalOpinionData");
            if (originalOpinion != null && !originalOpinion.isBlank() && !"null".equals(originalOpinion)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(originalOpinion, Map.class);
                    opinion.putAll(parsed);
                } catch (Exception exception) {
                    log.warn("解析加签原意见失败: taskId={}", settled.getTaskId());
                }
            }
            settleRequest.setOpinionData(opinion);
            taskActionService.getObject().execute(task.getTaskId(), settleRequest);
        } finally {
            LoginUserHolder.clear();
        }
        log.info("加签受控完成: taskId={}, negative={}", settled.getTaskId(), negative);
    }

    private void requireSignRecordTransition(int updated, String conflictMessage) {
        if (updated != 1) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(), conflictMessage);
        }
    }
}
