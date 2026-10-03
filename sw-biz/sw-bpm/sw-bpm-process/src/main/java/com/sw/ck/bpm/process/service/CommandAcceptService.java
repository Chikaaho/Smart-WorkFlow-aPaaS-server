package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.CommandFingerprint;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令受理服务：审批动作异步通道的统一受理入口。
 * <p>
 * 幂等：同一 (动作, 任务, 操作人) 在同一事务边界内重复提交返回同一受理标识
 *（duplicated=true），不产生第二条命令；FAILED 终态的受理允许重新提交。
 * </p>
 */
@Service
public class CommandAcceptService {

    private static final Logger log = LoggerFactory.getLogger(CommandAcceptService.class);
    /**
     * 仅收窄同一 JVM 内的同键受理竞争窗口；持久化唯一键仍是跨进程幂等真源，
     * 消费侧也不依赖此锁。请求结束后移除锁对象，避免按任务无限增长。
     */
    private static final ConcurrentHashMap<String, Object> ACCEPT_LOCKS = new ConcurrentHashMap<>();

    private final BpmCommandQueue commandQueue;
    private final ObjectMapper objectMapper;
    private final ResourceAdmissionService admissionService;

    public CommandAcceptService(BpmCommandQueue commandQueue, ObjectMapper objectMapper,
                                ResourceAdmissionService admissionService) {
        this.commandQueue = commandQueue;
        this.objectMapper = objectMapper;
        this.admissionService = admissionService;
    }

    /**
     * 受理审批动作命令（异步通道）。
     *
     * @param taskId 目标任务
     * @param action 审批动作（APPROVE/REJECT/RETURN）
     * @param request 动作内容（意见等，可为 null）
     * @param channel 通道（NORMAL；P0 由 P0 同步入口受理）
     */
    @Transactional
    public CommandAcceptRespDTO acceptTaskAction(String taskId, ApprovalAction action,
                                                 ApprovalActionRequest request,
                                                 CommandChannelEnum channel) {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        // 受理前租户边界（I5 收口）：命令信封承载租户语义，消费侧按信封租户还原身份并
        // 一致性校验；任何有效租户的命令均可受理，不再以「仅超租户可消费」为由拒绝。
        if (loginUser.getTenantId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "租户上下文缺失，不能提交审批命令");
        }
        CommandTypeEnum type = switch (action) {
            case APPROVE -> CommandTypeEnum.TASK_APPROVE;
            case REJECT -> CommandTypeEnum.TASK_REJECT;
            case RETURN -> CommandTypeEnum.TASK_RETURN;
            default -> throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        };
        String commandKey = type.getCode() + ":" + taskId + ":" + loginUser.getUserId();
        String lockKey = loginUser.getTenantId() + ":" + commandKey;
        Object lock = ACCEPT_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                return acceptTaskActionLocked(taskId, action, request, channel, loginUser,
                        type, commandKey);
            }
        } finally {
            ACCEPT_LOCKS.remove(lockKey, lock);
        }
    }

    private CommandAcceptRespDTO acceptTaskActionLocked(String taskId, ApprovalAction action,
                                                        ApprovalActionRequest request,
                                                        CommandChannelEnum channel,
                                                        LoginUser loginUser,
                                                        CommandTypeEnum type,
                                                        String commandKey) {
        String incomingPayload = toPayload(taskId, action, request);
        String incomingFingerprint = CommandFingerprint.of(incomingPayload);
        CommandEnvelope existing = commandQueue.findByKey(loginUser.getTenantId(), commandKey)
                .orElse(null);
        if (existing != null && !"FAILED".equals(existing.getStatus())) {
            // 同键命中（提示05 G3b1）：同载荷=同一操作重放，返回原受理；异载荷=明确拒绝，
            // 不得默认成功（含受理尚未完成/运行期的首次并发冲突）。旧行缺指纹时以存储
            // payload 原文回推指纹（可解释兼容），不迁移、不默认异载荷成功。
            String existingFingerprint = existing.getPayloadFingerprint() != null
                    && !existing.getPayloadFingerprint().isBlank()
                    ? existing.getPayloadFingerprint()
                    : CommandFingerprint.of(existing.getPayload());
            if (!incomingFingerprint.equals(existingFingerprint)) {
                log.warn("同键异载荷拒绝: key={}, existingCommandId={}, incomingFingerprint={}, "
                                + "existingFingerprint={}",
                        commandKey, existing.getCommandId(), incomingFingerprint, existingFingerprint);
                throw new BaseException(
                        com.sw.ck.bpm.api.exception.BpmErrorCode.COMMAND_PAYLOAD_MISMATCH);
            }
            log.info("审批命令幂等命中: key={}, commandId={}", commandKey, existing.getCommandId());
            return toResp(existing, false);
        }

        // FAILED 终态允许重新提交：唯一键 (tenant_id, command_key) 语义下复用同键行重置入队，
        // 不走新插（同键新插必撞唯一键且事务已污染，无法再走幂等返回）
        if (existing != null) {
            existing.setPayload(incomingPayload);
            existing.setPayloadFingerprint(incomingFingerprint);
            commandQueue.requeueFailed(existing);
            return toResp(existing, true);
        }

        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(type);
        envelope.setChannel(channel);
        envelope.setCommandKey(commandKey);
        envelope.setTenantId(loginUser.getTenantId());
        envelope.setInitiatorId(loginUser.getUserId());
        envelope.setPayload(incomingPayload);
        envelope.setPayloadFingerprint(incomingFingerprint);
        // 资源准入（P62 资源保障）：幂等回查之后、受理之前——同身份重放不重复占用额度；
        // 超限拒绝抛出后本事务整体回滚，不留下可执行命令、业务写入或成功幂等占位。
        // P0 通道=生产类（生产保留容量保护）；NORMAL=普通 OA 类。实时动作不占持久额度。
        ResourceAdmissionService.AdmissionTicket ticket = admissionService.admit(
                loginUser.getTenantId(),
                channel == CommandChannelEnum.P0
                        ? com.sw.ck.bpm.process.entity.ResourceClassEnum.PROD
                        : com.sw.ck.bpm.process.entity.ResourceClassEnum.OA,
                1, commandKey);
        if (ticket != null) {
            envelope.setResourceClass(channel == CommandChannelEnum.P0
                    ? com.sw.ck.bpm.process.entity.ResourceClassEnum.PROD.getCode()
                    : com.sw.ck.bpm.process.entity.ResourceClassEnum.OA.getCode());
            envelope.setResourceUnits(1);
            envelope.setResourceSegment(ticket.segment());
            envelope.setPolicyVersion(ticket.policyVersion());
        }
        try {
            commandQueue.enqueue(envelope);
        } catch (DuplicateKeyException e) {
            return commandQueue.findByKey(loginUser.getTenantId(), commandKey)
                    .map(env -> toResp(env, false))
                    .orElseThrow(() -> e);
        }
        return toResp(envelope, true);
    }

    private String toPayload(String taskId, ApprovalAction action, ApprovalActionRequest request) {
        try {
            ApprovalActionRequest effective = request == null ? new ApprovalActionRequest() : request;
            effective.setTaskId(taskId);
            // action 必须写入 payload：消费者据此执行对应审批动作（否则默认 APPROVE）
            effective.setAction(action);
            Map<String, Object> payload = new LinkedHashMap<>(objectMapper.convertValue(
                    effective, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
            payload.entrySet().removeIf(entry -> entry.getValue() == null);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化审批命令 payload 失败: taskId=" + taskId, e);
        }
    }

    private CommandAcceptRespDTO toResp(CommandEnvelope envelope, boolean duplicated) {
        CommandAcceptRespDTO resp = new CommandAcceptRespDTO();
        resp.setCommandId(envelope.getCommandId());
        resp.setCommandKey(envelope.getCommandKey());
        resp.setCommandType(envelope.getCommandType().getCode());
        resp.setChannel(envelope.getChannel().getCode());
        resp.setDuplicated(duplicated);
        return resp;
    }
}
