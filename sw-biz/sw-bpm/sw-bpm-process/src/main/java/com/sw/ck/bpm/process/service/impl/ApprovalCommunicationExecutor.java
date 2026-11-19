package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmCommunication;
import com.sw.ck.bpm.process.mapper.BpmCommunicationMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;

import java.time.LocalDateTime;
import java.util.Objects;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 沟通回复执行（自 ApprovalLifecycleServiceImpl 拆出，行为不变）：
 * 接收人身份与租户校验、幂等（已回复直接返回）。事务边界保留在
 * {@link ApprovalLifecycleServiceImpl} 门面的公开方法上。
 */
@Service
@Log4j2
class ApprovalCommunicationExecutor {

    @Autowired
    private BpmCommunicationMapper communicationMapper;
    @Autowired
    private ApprovalLifecycleSupport support;

    void replyCommunication(Long communicationId, ApprovalActionRequest request) {
        LoginUser actor = support.requireAuthenticatedActor();
        BpmCommunication comm = communicationMapper.selectById(communicationId);
        if (comm == null) {
            throw new BaseException(BpmErrorCode.COMMUNICATION_INVALID);
        }
        support.requireSameTenant(comm.getTenantId(), actor, "沟通记录不属于当前租户");
        if (!Objects.equals(comm.getReceiverId(), actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅沟通接收人可回复");
        }
        if ("REPLIED".equals(comm.getStatus())) {
            return; // 幂等
        }
        BpmCommunication patch = new BpmCommunication();
        patch.setId(comm.getId());
        patch.setStatus("REPLIED");
        patch.setReplyMessage(support.nullSafe(request.getMessage()));
        patch.setReplyTime(LocalDateTime.now());
        communicationMapper.updateById(patch);
        log.info("沟通已回复: communicationId={}, receiver={}", communicationId, actor.getUserId());
    }
}
