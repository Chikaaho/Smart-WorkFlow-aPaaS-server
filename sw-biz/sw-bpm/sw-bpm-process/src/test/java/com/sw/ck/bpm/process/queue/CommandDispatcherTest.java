package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.spi.UserDetailsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CommandDispatcher} 单元测试（mock 队列 + mock/自定义 Handler）。
 * <p>
 * 覆盖：正常领取处理、异常重试与终态回调、未知类型、handler 注册冲突、
 * 消费时身份上下文注入与 finally 清理。
 * </p>
 */
@DisplayName("命令调度器测试")
class CommandDispatcherTest {

    private final BpmCommandQueue commandQueue = mock(BpmCommandQueue.class);

    private final CommandDispatcher dispatcher =
            new CommandDispatcher(commandQueue, List.of());

    @BeforeEach
    void setUp() {
        configure(dispatcher);
        when(commandQueue.reclaimStale(any())).thenReturn(0);
    }

    /** 设置调度参数默认值（绕过 @Value 注入）。 */
    private CommandDispatcher configure(CommandDispatcher d) {
        org.springframework.test.util.ReflectionTestUtils.setField(d, "batchSize", 20);
        org.springframework.test.util.ReflectionTestUtils.setField(d, "p0BatchSize", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(d, "maxRetries", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(d, "backoffMillis", 1000L);
        org.springframework.test.util.ReflectionTestUtils.setField(d, "staleSeconds", 60L);
        return d;
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private CommandEnvelope envelope(CommandTypeEnum type) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandId(11L);
        envelope.setCommandType(type);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey(type.getCode() + ":k");
        envelope.setTenantId(1L);
        envelope.setInitiatorId(2L);
        envelope.setPayload("{}");
        envelope.setClaimToken("claim-token-11");
        return envelope;
    }

    // ==================== 正常处理 ====================

    @Test
    @DisplayName("pollNormal：领取并处理成功 → complete 被调、处理后 LoginUserHolder 已 clear")
    void pollNormal_shouldCompleteOnSuccess() throws Exception {
        BpmCommandHandler handler = mock(BpmCommandHandler.class);
        when(handler.types()).thenReturn(Set.of(CommandTypeEnum.FLOW_START));
        when(handler.handle(any())).thenReturn("{\"status\":\"STARTED\"}");
        CommandDispatcher d = configure(new CommandDispatcher(commandQueue, List.of(handler)));

        CommandEnvelope env = envelope(CommandTypeEnum.FLOW_START);
        when(commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 20)).thenReturn(List.of(env));

        d.pollNormal();

        verify(commandQueue).complete(eq(11L), org.mockito.ArgumentMatchers.any(), eq("{\"status\":\"STARTED\"}"));
        verify(commandQueue, never()).failAndScheduleRetry(anyLong(), anyString(), anyString(), anyInt(), anyLong());
        assertThat(LoginUserHolder.get()).isNull();
    }

    @Test
    @DisplayName("pollNormal：处理时把信封 tenantId/initiatorId 注入 LoginUserHolder")
    void pollNormal_shouldRestoreLoginUserDuringHandle() throws Exception {
        LoginUser captured = new LoginUser();
        BpmCommandHandler handler = new BpmCommandHandler() {
            @Override public Set<CommandTypeEnum> types() { return Set.of(CommandTypeEnum.FLOW_START); }
            @Override public String handle(CommandEnvelope e) {
                LoginUser user = LoginUserHolder.get();
                if (user != null) {
                    captured.setUserId(user.getUserId());
                    captured.setTenantId(user.getTenantId());
                }
                return "{}";
            }
        };
        CommandDispatcher d = configure(new CommandDispatcher(commandQueue, List.of(handler)));
        when(commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 20))
                .thenReturn(List.of(envelope(CommandTypeEnum.FLOW_START)));

        d.pollNormal();

        assertThat(captured.getUserId()).isEqualTo(2L);
        assertThat(captured.getTenantId()).isEqualTo(1L);
    }

    // ==================== 异常路径 ====================

    @Test
    @DisplayName("处理抛异常 → failAndScheduleRetry 被调；返回 false 时 onFinalFailure 被调")
    void pollNormal_shouldRetryAndCallOnFinalFailure() throws Exception {
        BpmCommandHandler handler = mock(BpmCommandHandler.class);
        when(handler.types()).thenReturn(Set.of(CommandTypeEnum.FLOW_START));
        when(handler.handle(any())).thenThrow(new RuntimeException("boom"));
        when(commandQueue.failAndScheduleRetry(eq(11L), anyString(), anyString(), anyInt(), anyLong())).thenReturn(false);
        CommandDispatcher d = configure(new CommandDispatcher(commandQueue, List.of(handler)));

        when(commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 20))
                .thenReturn(List.of(envelope(CommandTypeEnum.FLOW_START)));

        d.pollNormal();

        verify(commandQueue).failAndScheduleRetry(eq(11L), anyString(), anyString(), anyInt(), anyLong());
        verify(handler).onFinalFailure(any(CommandEnvelope.class),
                org.mockito.ArgumentMatchers.contains("boom"));
        verify(commandQueue, never()).complete(anyLong(), anyString(), anyString());
        assertThat(LoginUserHolder.get()).isNull();
    }

    @Test
    @DisplayName("处理抛异常但仍有重试额度 → 不调 onFinalFailure")
    void pollNormal_shouldNotCallOnFinalFailureWhenRetryScheduled() throws Exception {
        BpmCommandHandler handler = mock(BpmCommandHandler.class);
        when(handler.types()).thenReturn(Set.of(CommandTypeEnum.FLOW_START));
        when(handler.handle(any())).thenThrow(new RuntimeException("boom"));
        when(commandQueue.failAndScheduleRetry(eq(11L), anyString(), anyString(), anyInt(), anyLong())).thenReturn(true);
        CommandDispatcher d = configure(new CommandDispatcher(commandQueue, List.of(handler)));

        when(commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 20))
                .thenReturn(List.of(envelope(CommandTypeEnum.FLOW_START)));

        d.pollNormal();

        verify(handler, never()).onFinalFailure(any(), anyString());
    }

    @Test
    @DisplayName("未知命令类型 → failAndScheduleRetry 被调且 handler 不被调")
    void pollNormal_shouldFailUnknownCommandType() throws Exception {
        BpmCommandHandler handler = mock(BpmCommandHandler.class);
        when(handler.types()).thenReturn(Set.of(CommandTypeEnum.FLOW_START));
        CommandDispatcher d = configure(new CommandDispatcher(commandQueue, List.of(handler)));

        when(commandQueue.claimDue(List.of(CommandChannelEnum.NORMAL), 20))
                .thenReturn(List.of(envelope(CommandTypeEnum.TASK_RETURN)));

        d.pollNormal();

        verify(commandQueue).failAndScheduleRetry(eq(11L), anyString(), anyString(), anyInt(), anyLong());
        verify(handler, never()).handle(any());
    }

    // ==================== 注册冲突 ====================

    @Test
    @DisplayName("两个 handler 声明同一命令类型 → 构造抛 IllegalStateException")
    void constructor_shouldRejectDuplicateTypeRegistration() {
        BpmCommandHandler h1 = mock(BpmCommandHandler.class);
        BpmCommandHandler h2 = mock(BpmCommandHandler.class);
        when(h1.types()).thenReturn(Set.of(CommandTypeEnum.FLOW_START));
        when(h2.types()).thenReturn(Set.of(CommandTypeEnum.FLOW_START));

        assertThatThrownBy(() -> new CommandDispatcher(commandQueue, List.of(h1, h2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOW_START");
    }

    @Test
    @DisplayName("P0 消费前回查最新权限：撤权后拒绝且不进入 Handler")
    void p0ShouldRejectWhenPermissionRevokedAfterAccept() throws Exception {
        BpmCommandHandler handler = mock(BpmCommandHandler.class);
        when(handler.types()).thenReturn(Set.of(CommandTypeEnum.TASK_APPROVE));
        UserDetailsProvider provider = mock(UserDetailsProvider.class);
        LoginUser current = new LoginUser();
        current.setUserId(2L);
        current.setTenantId(1L);
        current.setPermissions(List.of());
        when(provider.loadByUserId(2L)).thenReturn(current);
        CommandDispatcher d = configure(new CommandDispatcher(commandQueue, List.of(handler), provider));
        CommandEnvelope env = envelope(CommandTypeEnum.TASK_APPROVE);
        env.setChannel(CommandChannelEnum.P0);
        when(commandQueue.claimDue(List.of(CommandChannelEnum.P0), 5)).thenReturn(List.of(env));

        d.pollP0();

        verify(commandQueue).reject(eq(11L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.contains("workflow:p0:dispatch"));
        verify(handler, never()).handle(any());
        assertThat(LoginUserHolder.get()).isNull();
    }
}
