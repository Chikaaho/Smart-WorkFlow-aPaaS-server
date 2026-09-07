package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 优先调度行为测试（B2 验收证据的调度层部分）。
 * <p>
 * 普通队列积压（NORMAL 领取返回空且不被消费）时，P0 车道领取的命令仍被
 * 独立处理——P0 与普通命令分车道领取，普通积压不阻塞 P0。
 * </p>
 */
@DisplayName("P0 独立车道优先调度测试")
class CommandDispatcherP0PriorityTest {

    @Test
    @DisplayName("普通积压时 P0 命令仍被独立车道处理")
    void p0ShouldProcessWhileNormalBacklogged() throws Exception {
        BpmCommandQueue queue = mock(BpmCommandQueue.class);
        BpmCommandHandler handler = mock(BpmCommandHandler.class);
        when(handler.types()).thenReturn(java.util.Set.of(CommandTypeEnum.TASK_APPROVE));

        // 普通车道积压：领取不到可处理命令
        when(queue.claimDue(List.of(CommandChannelEnum.NORMAL), 20)).thenReturn(List.of());
        // P0 车道有一条待处理命令
        CommandEnvelope p0 = new CommandEnvelope();
        p0.setCommandId(1L);
        p0.setCommandType(CommandTypeEnum.TASK_APPROVE);
        p0.setChannel(CommandChannelEnum.P0);
        p0.setCommandKey("TASK_APPROVE:task-1:7");
        p0.setTenantId(1L);
        p0.setInitiatorId(7L);
        when(queue.claimDue(List.of(CommandChannelEnum.P0), 5)).thenReturn(List.of(p0));

        CommandDispatcher dispatcher = newDispatcher(queue, handler);
        dispatcher.pollNormal();
        dispatcher.pollP0();

        // 普通轮询未处理任何东西；P0 命令被领取、处理并确认
        verify(queue).claimDue(List.of(CommandChannelEnum.NORMAL), 20);
        verify(queue).claimDue(List.of(CommandChannelEnum.P0), 5);
        verify(handler).handle(p0);
        verify(queue).complete(eq(1L), any(), any());
        assertThat(LoginUserHolder.get()).isNull();
    }

    @Test
    @DisplayName("P0 车道与普通车道领取互不重叠（P0 调度器只领 P0）")
    void p0LaneShouldNotClaimNormal() {
        BpmCommandQueue queue = mock(BpmCommandQueue.class);
        BpmCommandHandler handler = mock(BpmCommandHandler.class);
        when(handler.types()).thenReturn(java.util.Set.of(CommandTypeEnum.TASK_APPROVE));
        when(queue.claimDue(anyList(), anyInt())).thenReturn(List.of());

        CommandDispatcher dispatcher = newDispatcher(queue, handler);
        dispatcher.pollP0();

        verify(queue).claimDue(List.of(CommandChannelEnum.P0), 5);
        verify(queue, never()).claimDue(eq(List.of(CommandChannelEnum.NORMAL)), anyInt());
    }

    private CommandDispatcher newDispatcher(BpmCommandQueue queue, BpmCommandHandler handler) {
        CommandDispatcher dispatcher = new CommandDispatcher(queue, List.of(handler));
        org.springframework.test.util.ReflectionTestUtils.setField(dispatcher, "batchSize", 20);
        org.springframework.test.util.ReflectionTestUtils.setField(dispatcher, "p0BatchSize", 5);
        return dispatcher;
    }
}