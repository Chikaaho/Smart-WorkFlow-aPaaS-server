package com.sw.ck.bpm.process.queue;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.service.BpmCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PersistentBpmCommandQueue} 单元测试（mock {@link BpmCommandService}）。
 * <p>
 * 覆盖：enqueue 初始状态、claimDue 竞争领取语义、complete/failAndScheduleRetry
 * 状态机与有界重试、findByKey/findById 信封映射。
 * </p>
 */
@DisplayName("持久化命令队列测试")
class CommandQueueTest {

    private final BpmCommandService commandService = mock(BpmCommandService.class);
    private final LambdaQueryChainWrapper<BpmCommand> queryChain = mock(LambdaQueryChainWrapper.class);
    private final LambdaUpdateChainWrapper<BpmCommand> updateChain = mock(LambdaUpdateChainWrapper.class);

    private final PersistentBpmCommandQueue queue = new PersistentBpmCommandQueue(commandService);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(commandService.lambdaQuery()).thenReturn((LambdaQueryChainWrapper) queryChain);
        when(commandService.lambdaUpdate()).thenReturn((LambdaUpdateChainWrapper) updateChain);
        when(queryChain.eq(any(SFunction.class), any())).thenReturn(queryChain);
        when(queryChain.in(any(SFunction.class), anyCollection())).thenReturn(queryChain);
        when(queryChain.and(any())).thenReturn(queryChain);
        when(queryChain.orderByAsc(any(SFunction.class))).thenReturn(queryChain);
        when(queryChain.last(anyString())).thenReturn(queryChain);
        when(updateChain.eq(any(SFunction.class), any())).thenReturn(updateChain);
        when(updateChain.set(any(SFunction.class), any())).thenReturn(updateChain);
        when(updateChain.update()).thenReturn(true);
    }

    private CommandEnvelope envelope() {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.TASK_APPROVE);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey("TASK_APPROVE:t1:2");
        envelope.setTenantId(1L);
        envelope.setInitiatorId(2L);
        envelope.setPayload("{}");
        return envelope;
    }

    private BpmCommand command(long id) {
        BpmCommand command = new BpmCommand();
        command.setId(id);
        command.setCommandKey("TASK_APPROVE:t1:2");
        command.setCommandType(CommandTypeEnum.TASK_APPROVE.getCode());
        command.setChannel(CommandChannelEnum.NORMAL.getCode());
        command.setStatus(CommandStatusEnum.PENDING.getCode());
        command.setPayload("{}");
        command.setRetryCount(0);
        command.setTenantId(1L);
        command.setInitiatorId(2L);
        return command;
    }

    // ==================== enqueue ====================

    @Test
    @DisplayName("enqueue：生成 commandId、初始 PENDING、channel/retryCount/initiator 正确")
    void enqueue_shouldPersistPendingCommand() {
        when(commandService.save(any(BpmCommand.class))).thenAnswer(inv -> {
            BpmCommand saved = inv.getArgument(0);
            saved.setId(77L);
            return true;
        });

        CommandEnvelope envelope = envelope();
        Long id = queue.enqueue(envelope);

        assertThat(id).isEqualTo(77L);
        assertThat(envelope.getCommandId()).isEqualTo(77L);
        ArgumentCaptor<BpmCommand> captor = ArgumentCaptor.forClass(BpmCommand.class);
        verify(commandService).save(captor.capture());
        BpmCommand saved = captor.getValue();
        assertThat(saved.getCommandKey()).isEqualTo("TASK_APPROVE:t1:2");
        assertThat(saved.getCommandType()).isEqualTo(CommandTypeEnum.TASK_APPROVE.getCode());
        assertThat(saved.getChannel()).isEqualTo(CommandChannelEnum.NORMAL.getCode());
        assertThat(saved.getStatus()).isEqualTo(CommandStatusEnum.PENDING.getCode());
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getInitiatorId()).isEqualTo(2L);
        assertThat(saved.getPayload()).isEqualTo("{}");
    }

    // ==================== claimDue ====================

    @Test
    @DisplayName("claimDue：候选领取成功 → status=PROCESSING 出现在结果中")
    void claimDue_shouldReturnClaimedEnvelope() {
        BpmCommand candidate = command(1L);
        when(queryChain.list()).thenReturn(List.of(candidate));
        when(commandService.update(any(Wrapper.class))).thenReturn(true);

        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10);

        assertThat(claimed).hasSize(1);
        CommandEnvelope envelope = claimed.get(0);
        assertThat(envelope.getCommandId()).isEqualTo(1L);
        assertThat(envelope.getStatus()).isEqualTo(CommandStatusEnum.PROCESSING.getCode());
        assertThat(envelope.getCommandType()).isEqualTo(CommandTypeEnum.TASK_APPROVE);
        assertThat(envelope.getChannel()).isEqualTo(CommandChannelEnum.NORMAL);
    }

    @Test
    @DisplayName("claimDue：条件更新失败（被竞争者抢走）→ 不得出现在结果中")
    void claimDue_shouldSkipWhenClaimUpdateFails() {
        BpmCommand candidate = command(1L);
        when(queryChain.list()).thenReturn(List.of(candidate));
        when(commandService.update(any(Wrapper.class))).thenReturn(false);

        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10);

        assertThat(claimed).isEmpty();
    }

    // ==================== complete ====================

    @Test
    @DisplayName("complete：写 COMPLETED + result")
    void complete_shouldWriteCompletedAndResult() {
        queue.complete(1L, "token-1", "{\"status\":\"DONE\"}");

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(updateChain, org.mockito.Mockito.atLeastOnce()).set(any(SFunction.class), values.capture());
        assertThat(values.getAllValues()).contains(CommandStatusEnum.COMPLETED.getCode(), "{\"status\":\"DONE\"}");
        verify(updateChain).update();
    }

    // ==================== failAndScheduleRetry ====================

    @Test
    @DisplayName("failAndScheduleRetry：未达上限 → 返回 true，置回 PENDING 且 retry_count+1")
    void failAndScheduleRetry_shouldScheduleRetryWhenBelowLimit() {
        BpmCommand command = command(1L);
        command.setRetryCount(0);
        when(commandService.getById(1L)).thenReturn(command);

        boolean retried = queue.failAndScheduleRetry(1L, null, "boom", 3, 1000L);

        assertThat(retried).isTrue();
        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(updateChain, org.mockito.Mockito.atLeastOnce()).set(any(SFunction.class), values.capture());
        assertThat(values.getAllValues()).contains(CommandStatusEnum.PENDING.getCode(), 1);
        assertThat(values.getAllValues()).anySatisfy(v -> assertThat(v).isInstanceOf(LocalDateTime.class));
    }

    @Test
    @DisplayName("failAndScheduleRetry：达到 maxRetries → 返回 false（FAILED 终态）")
    void failAndScheduleRetry_shouldFailFinalWhenLimitReached() {
        BpmCommand command = command(1L);
        command.setRetryCount(2);
        when(commandService.getById(1L)).thenReturn(command);

        boolean retried = queue.failAndScheduleRetry(1L, null, "boom", 3, 1000L);

        assertThat(retried).isFalse();
        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(updateChain, org.mockito.Mockito.atLeastOnce()).set(any(SFunction.class), values.capture());
        assertThat(values.getAllValues()).contains(CommandStatusEnum.FAILED.getCode(), "boom");
        assertThat(values.getAllValues()).noneMatch(CommandStatusEnum.PENDING.getCode()::equals);
    }

    @Test
    @DisplayName("failAndScheduleRetry：命令不存在 → 返回 false")
    void failAndScheduleRetry_shouldReturnFalseWhenCommandMissing() {
        when(commandService.getById(1L)).thenReturn(null);
        assertThat(queue.failAndScheduleRetry(1L, null, "boom", 3, 1000L)).isFalse();
    }

    // ==================== findByKey / findById ====================

    @Test
    @DisplayName("findByKey：命中时信封映射正确（含 status/result/failureReason）")
    void findByKey_shouldMapEnvelope() {
        BpmCommand command = command(9L);
        command.setStatus(CommandStatusEnum.COMPLETED.getCode());
        command.setResult("{\"ok\":true}");
        command.setFailureReason("earlier-failure");
        command.setRetryCount(2);
        when(queryChain.one()).thenReturn(command);

        Optional<CommandEnvelope> found = queue.findByKey(1L, "TASK_APPROVE:t1:2");

        assertThat(found).isPresent();
        CommandEnvelope envelope = found.get();
        assertThat(envelope.getCommandId()).isEqualTo(9L);
        assertThat(envelope.getCommandType()).isEqualTo(CommandTypeEnum.TASK_APPROVE);
        assertThat(envelope.getChannel()).isEqualTo(CommandChannelEnum.NORMAL);
        assertThat(envelope.getCommandKey()).isEqualTo("TASK_APPROVE:t1:2");
        assertThat(envelope.getTenantId()).isEqualTo(1L);
        assertThat(envelope.getInitiatorId()).isEqualTo(2L);
        assertThat(envelope.getPayload()).isEqualTo("{}");
        assertThat(envelope.getRetryCount()).isEqualTo(2);
        assertThat(envelope.getStatus()).isEqualTo(CommandStatusEnum.COMPLETED.getCode());
        assertThat(envelope.getResult()).isEqualTo("{\"ok\":true}");
        assertThat(envelope.getFailureReason()).isEqualTo("earlier-failure");
    }

    @Test
    @DisplayName("findByKey：未命中 → Optional.empty")
    void findByKey_shouldReturnEmptyWhenMissing() {
        when(queryChain.one()).thenReturn(null);
        assertThat(queue.findByKey(1L, "nope")).isEmpty();
    }

    @Test
    @DisplayName("findById：命中时信封映射正确；retryCount 为 null 时按 0 处理")
    void findById_shouldMapEnvelopeWithNullRetryAsZero() {
        BpmCommand command = command(5L);
        command.setRetryCount(null);
        command.setStatus(CommandStatusEnum.FAILED.getCode());
        command.setFailureReason("final");
        when(commandService.getById(5L)).thenReturn(command);

        Optional<CommandEnvelope> found = queue.findById(5L);

        assertThat(found).isPresent();
        assertThat(found.get().getRetryCount()).isZero();
        assertThat(found.get().getStatus()).isEqualTo(CommandStatusEnum.FAILED.getCode());
        assertThat(found.get().getFailureReason()).isEqualTo("final");
    }
}
