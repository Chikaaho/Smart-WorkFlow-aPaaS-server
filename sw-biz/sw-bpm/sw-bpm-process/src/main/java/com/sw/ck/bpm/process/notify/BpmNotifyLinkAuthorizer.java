package com.sw.ck.bpm.process.notify;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.notify.api.NotifyLinkAuthorizer;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.ApprovalActionRecordMapper;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.bpm.process.mapper.CopyRecordMapper;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * I6 深链对象权限裁决（方向 §6-11：发起人/参与人/抄送接收人可开，其余 fail closed）。
 * <p>
 * WF_TASK：任务可处理（assignee/candidate）或曾是该任务动作记录人；
 * WF_PROCESS：实例发起人、动作参与人（actor/target）或抄送接收人。
 * 无记录/无上下文一律拒绝——不泄露对象存在性；
 * 上下文缺失以 {@code Optional.empty()} 表达，调用方必须按拒绝处理（fail closed）。
 * </p>
 */
@Component
public class BpmNotifyLinkAuthorizer implements NotifyLinkAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(BpmNotifyLinkAuthorizer.class);

    @Autowired
    private BpmInstanceMapper instanceMapper;

    @Autowired
    private ApprovalActionRecordMapper approvalActionRecordMapper;

    @Autowired
    private CopyRecordMapper copyRecordMapper;

    @Override
    public Optional<Boolean> canOpen(String linkType, String linkId) {
        Long userId = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId();
        if (userId == null || linkType == null || linkId == null || linkId.isBlank()) {
            // 缺少裁决上下文：fail closed，由调用方统一按拒绝处理
            return Optional.empty();
        }
        boolean allowed;
        if ("WF_PROCESS".equals(linkType)) {
            allowed = canOpenProcess(linkId, userId);
        } else if ("WF_TASK".equals(linkType)) {
            allowed = canOpenTask(linkId, userId);
        } else {
            allowed = false;
        }
        if (!allowed) {
            log.info("深链对象权限拒绝: linkType={}, userId={}", linkType, userId);
        }
        return Optional.of(allowed);
    }

    private boolean canOpenProcess(String processInstanceId, Long userId) {
        BpmInstance instance = instanceMapper.selectOne(Wrappers.<BpmInstance>lambdaQuery()
                .eq(BpmInstance::getProcessInstanceId, processInstanceId).last("LIMIT 1"));
        if (instance == null) {
            return false;
        }
        if (userId.equals(instance.getInitiatorId())) {
            return true;
        }
        Long actorActions = approvalActionRecordMapper.selectCount(Wrappers.<com.sw.ck.bpm.process.entity.ApprovalActionRecord>lambdaQuery()
                .eq(com.sw.ck.bpm.process.entity.ApprovalActionRecord::getProcessInstanceId, processInstanceId)
                .eq(com.sw.ck.bpm.process.entity.ApprovalActionRecord::getActorId, userId)
                .last("LIMIT 1"));
        if (actorActions != null && actorActions > 0) {
            return true;
        }
        Long copies = copyRecordMapper.selectCount(Wrappers.<com.sw.ck.bpm.process.entity.CopyRecord>lambdaQuery()
                .eq(com.sw.ck.bpm.process.entity.CopyRecord::getProcessInstanceId, processInstanceId)
                .eq(com.sw.ck.bpm.process.entity.CopyRecord::getRecipientId, String.valueOf(userId))
                .last("LIMIT 1"));
        return copies != null && copies > 0;
    }

    private boolean canOpenTask(String taskId, Long userId) {
        // 任务深链：动作记录存在（本人是该任务的动作/收件记录人）即允许查看
        Long actions = approvalActionRecordMapper.selectCount(Wrappers.<com.sw.ck.bpm.process.entity.ApprovalActionRecord>lambdaQuery()
                .eq(com.sw.ck.bpm.process.entity.ApprovalActionRecord::getTaskId, taskId)
                .and(w -> w.eq(com.sw.ck.bpm.process.entity.ApprovalActionRecord::getActorId, userId)
                        .or().eq(com.sw.ck.bpm.process.entity.ApprovalActionRecord::getTargetUserId, userId))
                .last("LIMIT 1"));
        return actions != null && actions > 0;
    }
}
