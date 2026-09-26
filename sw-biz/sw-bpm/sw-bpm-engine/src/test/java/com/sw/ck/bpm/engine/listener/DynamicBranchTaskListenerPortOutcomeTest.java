package com.sw.ck.bpm.engine.listener;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.api.result.MutationOutcome;
import org.flowable.engine.RuntimeService;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AM-107 调用方行为证据（G1 完成条件 4）：{@code DynamicBranchPort#recordAction} 的
 * present 与 empty 两条路径都必须被调用方显式消费——未冻结分支不得被静默当作成功。
 * <p>
 * 断言方式：捕获 {@link DynamicBranchTaskListener} 自身的日志事件（真实实现，非桩）：
 * present 路径不得出现“未落账”告警；empty 路径必须出现明确的“未落账/分支快照尚未冻结”告警。
 * </p>
 */
class DynamicBranchTaskListenerPortOutcomeTest {

    private final DynamicBranchPort port = mock(DynamicBranchPort.class);
    private final ParticipantSnapshotRecorder recorder = mock(ParticipantSnapshotRecorder.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger listenerLogger;
    private DynamicBranchTaskListener listener;

    @BeforeEach
    void setUp() {
        listenerLogger = (Logger) LoggerFactory.getLogger(DynamicBranchTaskListener.class);
        appender.list.clear();
        appender.start();
        listenerLogger.addAppender(appender);

        when(recorder.record(anyString(), anyString(), anyString(), anyList(), anyMap(), any()))
                .thenReturn(Optional.of(MutationOutcome.APPLIED));
        listener = new DynamicBranchTaskListener(
                mock(RuntimeService.class),
                providerOf(recorder),
                providerOf(mock(ConsensusVotePort.class)),
                providerOf(port));
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("present：动作已落账（APPLIED）时按结果消费，不产生“未落账”告警")
    void presentOutcomeIsConsumedWithoutUnrecordedWarning() {
        when(port.recordAction(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(Optional.of(MutationOutcome.APPLIED));

        listener.notify(createTask());

        verify(port).recordAction("1", "pi-1", "dyn-node", "7", "task-1", "START", null);
        assertThat(warnings())
                .as("present 路径不得报告未落账")
                .noneMatch(message -> message.contains("未落账"));
    }

    @Test
    @DisplayName("present（幂等）：ALREADY_APPLIED 被识别为已有动作，同样不算未落账")
    void alreadyAppliedOutcomeIsRecognizedAsIdempotent() {
        when(port.recordAction(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(Optional.of(MutationOutcome.ALREADY_APPLIED));

        listener.notify(createTask());

        verify(port).recordAction("1", "pi-1", "dyn-node", "7", "task-1", "START", null);
        assertThat(warnings()).noneMatch(message -> message.contains("未落账"));
    }

    @Test
    @DisplayName("empty：分支快照尚未冻结 → 显式告警并说明未落账，不静默当作成功")
    void emptyOutcomeIsReportedInsteadOfSilentlyTreatedAsSuccess() {
        when(port.recordAction(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(Optional.empty());

        listener.notify(createTask());

        verify(port).recordAction("1", "pi-1", "dyn-node", "7", "task-1", "START", null);
        assertThat(warnings())
                .as("empty 路径必须显式报告未落账，且指明原因（分支快照尚未冻结）")
                .anyMatch(message -> message.contains("动态分支动作未落账")
                        && message.contains("分支快照尚未冻结")
                        && message.contains("dyn-node")
                        && message.contains("action=START"));
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() != null && "WARN".equals(event.getLevel().toString()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static DelegateTask createTask() {
        DelegateTask task = mock(DelegateTask.class);
        when(task.getEventName()).thenReturn("create");
        when(task.getVariableLocal("participantId")).thenReturn("7");
        when(task.getVariable("participantId")).thenReturn("7");
        when(task.getVariable("tenantId")).thenReturn("1");
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        when(task.getTaskDefinitionKey()).thenReturn("dyn-node");
        when(task.getId()).thenReturn("task-1");
        return task;
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
