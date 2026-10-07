package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CommandAcceptService} 单元测试。
 * <p>
 * 覆盖：审批动作受理（类型/幂等键/通道映射）、同 key 幂等命中、FAILED 后重新受理。
 * </p>
 */
@DisplayName("命令受理服务测试")
class CommandAcceptServiceTest {

    private final BpmCommandQueue commandQueue = mock(BpmCommandQueue.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService =
            mock(com.sw.ck.bpm.process.service.ResourceAdmissionService.class);

    private final CommandAcceptService service =
            new CommandAcceptService(commandQueue, objectMapper, admissionService);

    @BeforeEach
    void setUp() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(2L);
        loginUser.setTenantId(0L);
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    /** 与 {@code CommandAcceptService.toPayload} 同构：构造 (task-9, APPROVE, 空请求) 的载荷原文。 */
    private String payloadJsonOfTask9Approve() {
        try {
            ApprovalActionRequest effective = new ApprovalActionRequest();
            effective.setTaskId("task-9");
            effective.setAction(ApprovalAction.APPROVE);
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>(
                    objectMapper.convertValue(effective,
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
            payload.entrySet().removeIf(entry -> entry.getValue() == null);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("I5 收口：非零租户命令正常受理，信封携带发起租户（消费侧按信封租户还原身份）")
    void acceptTaskAction_shouldAcceptNonSuperTenantWithEnvelopeTenant() {
        LoginUser tenant5 = new LoginUser();
        tenant5.setUserId(2L);
        tenant5.setTenantId(5L);
        LoginUserHolder.set(tenant5);
        when(commandQueue.findByKey(5L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.empty());
        when(commandQueue.enqueue(any(CommandEnvelope.class))).thenAnswer(inv -> {
            CommandEnvelope env = inv.getArgument(0);
            env.setCommandId(66L);
            return 66L;
        });

        CommandAcceptRespDTO resp = service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                null, CommandChannelEnum.NORMAL);

        assertThat(resp.getCommandId()).isEqualTo(66L);
        ArgumentCaptor<CommandEnvelope> captor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue).enqueue(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(5L);
        assertThat(captor.getValue().getInitiatorId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("首次受理：enqueue 收到正确 type/commandKey/channel，duplicated=true")
    void acceptTaskAction_shouldEnqueueNewCommand() {
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.empty());
        when(commandQueue.enqueue(any(CommandEnvelope.class))).thenAnswer(inv -> {
            CommandEnvelope env = inv.getArgument(0);
            env.setCommandId(66L);
            return 66L;
        });

        CommandAcceptRespDTO resp = service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                null, CommandChannelEnum.NORMAL);

        assertThat(resp.getCommandId()).isEqualTo(66L);
        assertThat(resp.getCommandKey()).isEqualTo("TASK_APPROVE:task-9:2");
        assertThat(resp.getCommandType()).isEqualTo(CommandTypeEnum.TASK_APPROVE.getCode());
        assertThat(resp.getChannel()).isEqualTo(CommandChannelEnum.NORMAL.getCode());
        assertThat(resp.isDuplicated()).isTrue();

        ArgumentCaptor<CommandEnvelope> captor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue).enqueue(captor.capture());
        CommandEnvelope env = captor.getValue();
        assertThat(env.getCommandType()).isEqualTo(CommandTypeEnum.TASK_APPROVE);
        assertThat(env.getChannel()).isEqualTo(CommandChannelEnum.NORMAL);
        assertThat(env.getCommandKey()).isEqualTo("TASK_APPROVE:task-9:2");
        assertThat(env.getTenantId()).isEqualTo(0L);
        assertThat(env.getInitiatorId()).isEqualTo(2L);
        assertThat(env.getPayload()).contains("task-9");
    }

    @Test
    @DisplayName("同 key 已存在且状态非 FAILED → 同载荷幂等命中返回原受理且不再 enqueue（提示05 G3b1）")
    void acceptTaskAction_shouldReturnExistingWhenNotFailed() {
        // 第一次受理：捕获入队信封（payload 与 payload_fingerprint 由受理层写入）
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.empty());
        when(commandQueue.enqueue(any(CommandEnvelope.class))).thenAnswer(inv -> {
            CommandEnvelope env = inv.getArgument(0);
            env.setCommandId(66L);
            return 66L;
        });
        service.acceptTaskAction("task-9", ApprovalAction.APPROVE, null, CommandChannelEnum.NORMAL);
        ArgumentCaptor<CommandEnvelope> captor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue).enqueue(captor.capture());
        CommandEnvelope existing = captor.getValue();
        existing.setCommandId(66L);
        existing.setStatus("PROCESSING");

        // 第二次同载荷受理：幂等命中返回原命令
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.of(existing));
        CommandAcceptRespDTO resp = service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                null, CommandChannelEnum.NORMAL);

        assertThat(resp.getCommandId()).isEqualTo(66L);
        assertThat(resp.isDuplicated()).isFalse();
        verify(commandQueue, org.mockito.Mockito.times(1)).enqueue(any());
    }

    @Test
    @DisplayName("同 key 异载荷（含运行期在途命令）→ 明确拒绝载荷冲突，不吞成成功（提示05 G3b1）")
    void acceptTaskAction_shouldRejectSameKeyDifferentPayload() {
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.empty());
        when(commandQueue.enqueue(any(CommandEnvelope.class))).thenAnswer(inv -> {
            CommandEnvelope env = inv.getArgument(0);
            env.setCommandId(66L);
            return 66L;
        });
        service.acceptTaskAction("task-9", ApprovalAction.APPROVE, null, CommandChannelEnum.NORMAL);
        ArgumentCaptor<CommandEnvelope> captor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue).enqueue(captor.capture());
        CommandEnvelope existing = captor.getValue();
        existing.setCommandId(66L);
        existing.setStatus("PROCESSING");
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.of(existing));

        // 同键、comment 不同的重放：必须拒绝（COMMAND_PAYLOAD_MISMATCH），不得返回原命令
        ApprovalActionRequest different = new ApprovalActionRequest();
        different.setComment("第二载荷");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                                different, CommandChannelEnum.NORMAL))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不同的请求载荷");
        verify(commandQueue, never()).requeueFailed(any());
    }

    @Test
    @DisplayName("已存在且状态 EXPIRED（效果未发生）→ 同键同载荷生成恢复命令新行（原EXPIRED行不改写，P63 G05b 修正）")
    void acceptTaskAction_shouldCreateRecoveryCommandWhenExpired() {
        CommandEnvelope existing = new CommandEnvelope();
        existing.setCommandId(66L);
        existing.setCommandType(CommandTypeEnum.TASK_APPROVE);
        existing.setChannel(CommandChannelEnum.NORMAL);
        existing.setCommandKey("TASK_APPROVE:task-9:2");
        existing.setStatus("EXPIRED");
        existing.setPayload(payloadJsonOfTask9Approve());
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.of(existing));
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2:R1")).thenReturn(Optional.empty());
        when(commandQueue.enqueue(any(CommandEnvelope.class))).thenAnswer(inv -> {
            CommandEnvelope env = inv.getArgument(0);
            env.setCommandId(77L);
            return 77L;
        });

        CommandAcceptRespDTO resp = service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                new ApprovalActionRequest(), CommandChannelEnum.NORMAL);

        assertThat(resp.getCommandId()).isEqualTo(77L);
        assertThat(resp.getCommandKey()).isEqualTo("TASK_APPROVE:task-9:2:R1");
        ArgumentCaptor<CommandEnvelope> captor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue).enqueue(captor.capture());
        assertThat(captor.getValue().getCommandKey()).isEqualTo("TASK_APPROVE:task-9:2:R1");
        // 原 EXPIRED 行不得被改写或重排
        verify(commandQueue, never()).requeueFailed(any());
    }

    @Test
    @DisplayName("EXPIRED 恢复链：恢复命令已存在且在途 → 幂等命中恢复行，不自动重发")
    void acceptTaskAction_shouldHitRecoveryChainWhenInFlight() {
        CommandEnvelope existing = new CommandEnvelope();
        existing.setCommandId(66L);
        existing.setCommandType(CommandTypeEnum.TASK_APPROVE);
        existing.setChannel(CommandChannelEnum.NORMAL);
        existing.setCommandKey("TASK_APPROVE:task-9:2");
        existing.setStatus("EXPIRED");
        existing.setPayload(payloadJsonOfTask9Approve());
        CommandEnvelope recovery = new CommandEnvelope();
        recovery.setCommandId(77L);
        recovery.setCommandType(CommandTypeEnum.TASK_APPROVE);
        recovery.setChannel(CommandChannelEnum.NORMAL);
        recovery.setCommandKey("TASK_APPROVE:task-9:2:R1");
        recovery.setStatus("PENDING");
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.of(existing));
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2:R1")).thenReturn(Optional.of(recovery));

        CommandAcceptRespDTO resp = service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                new ApprovalActionRequest(), CommandChannelEnum.NORMAL);

        assertThat(resp.getCommandId()).isEqualTo(77L);
        assertThat(resp.isDuplicated()).isFalse();
        verify(commandQueue, never()).enqueue(any());
        verify(commandQueue, never()).requeueFailed(any());
    }

    @Test
    @DisplayName("EXPIRED 恢复链：恢复命令也已过期 → 生成下一代 :R2（每代原记录独立保留）")
    void acceptTaskAction_shouldChainNextGenerationWhenRecoveryExpired() {
        CommandEnvelope existing = new CommandEnvelope();
        existing.setCommandId(66L);
        existing.setCommandType(CommandTypeEnum.TASK_APPROVE);
        existing.setChannel(CommandChannelEnum.NORMAL);
        existing.setCommandKey("TASK_APPROVE:task-9:2");
        existing.setStatus("EXPIRED");
        existing.setPayload(payloadJsonOfTask9Approve());
        CommandEnvelope recoveryExpired = new CommandEnvelope();
        recoveryExpired.setCommandId(77L);
        recoveryExpired.setCommandType(CommandTypeEnum.TASK_APPROVE);
        recoveryExpired.setChannel(CommandChannelEnum.NORMAL);
        recoveryExpired.setCommandKey("TASK_APPROVE:task-9:2:R1");
        recoveryExpired.setStatus("EXPIRED");
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.of(existing));
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2:R1"))
                .thenReturn(Optional.of(recoveryExpired));
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2:R2")).thenReturn(Optional.empty());
        when(commandQueue.enqueue(any(CommandEnvelope.class))).thenAnswer(inv -> {
            CommandEnvelope env = inv.getArgument(0);
            env.setCommandId(88L);
            return 88L;
        });

        CommandAcceptRespDTO resp = service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                new ApprovalActionRequest(), CommandChannelEnum.NORMAL);

        assertThat(resp.getCommandId()).isEqualTo(88L);
        assertThat(resp.getCommandKey()).isEqualTo("TASK_APPROVE:task-9:2:R2");
        verify(commandQueue, never()).requeueFailed(any());
    }

    @Test
    @DisplayName("已存在且状态 FAILED → 复用同键行 requeueFailed 重新入队（唯一键语义下不新插）")
    void acceptTaskAction_shouldReEnqueueWhenFailed() {
        CommandEnvelope existing = new CommandEnvelope();
        existing.setCommandId(66L);
        existing.setCommandType(CommandTypeEnum.TASK_APPROVE);
        existing.setChannel(CommandChannelEnum.NORMAL);
        existing.setCommandKey("TASK_APPROVE:task-9:2");
        existing.setStatus("FAILED");
        existing.setPayload(payloadJsonOfTask9Approve());
        when(commandQueue.findByKey(0L, "TASK_APPROVE:task-9:2")).thenReturn(Optional.of(existing));
        when(commandQueue.requeueFailed(any(CommandEnvelope.class))).thenAnswer(inv -> {
            CommandEnvelope env = inv.getArgument(0);
            env.setCommandId(66L);
            return 66L;
        });

        CommandAcceptRespDTO resp = service.acceptTaskAction("task-9", ApprovalAction.APPROVE,
                new ApprovalActionRequest(), CommandChannelEnum.NORMAL);

        assertThat(resp.getCommandId()).isEqualTo(66L);
        assertThat(resp.isDuplicated()).isTrue();
        verify(commandQueue).requeueFailed(any(CommandEnvelope.class));
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("动作映射：REJECT/RETURN → 对应命令类型")
    void acceptTaskAction_shouldMapActionTypes() {
        when(commandQueue.findByKey(eq(0L), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(commandQueue.enqueue(any(CommandEnvelope.class))).thenReturn(1L);

        service.acceptTaskAction("task-9", ApprovalAction.REJECT, null, CommandChannelEnum.NORMAL);
        ArgumentCaptor<CommandEnvelope> captor = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(commandQueue, org.mockito.Mockito.times(1)).enqueue(captor.capture());
        assertThat(captor.getValue().getCommandType()).isEqualTo(CommandTypeEnum.TASK_REJECT);

        service.acceptTaskAction("task-9", ApprovalAction.RETURN, null, CommandChannelEnum.NORMAL);
        verify(commandQueue, org.mockito.Mockito.times(2)).enqueue(captor.capture());
        assertThat(captor.getValue().getCommandType()).isEqualTo(CommandTypeEnum.TASK_RETURN);
    }
}
