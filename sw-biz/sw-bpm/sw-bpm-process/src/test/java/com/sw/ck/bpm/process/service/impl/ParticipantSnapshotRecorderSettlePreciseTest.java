package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算失效面按运行时任务存活收窄：会签多实例存活任务保持 PENDING，
 * 仅任务已消失（共享任务被取消）的候选失效。
 */
@ExtendWith(MockitoExtension.class)
class ParticipantSnapshotRecorderSettlePreciseTest {

    @Mock
    private ParticipantSnapshotMapper mapper;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BpmTaskFacade> providerOf(BpmTaskFacade facade) {
        ObjectProvider<BpmTaskFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facade);
        return provider;
    }

    private static ParticipantSnapshot row(long id, String taskId, String participantId) {
        ParticipantSnapshot row = new ParticipantSnapshot();
        row.setId(id);
        row.setTaskId(taskId);
        row.setParticipantId(participantId);
        row.setParticipantStatus("PENDING");
        return row;
    }

    @Test
    void settleKeepsPendingWhenSiblingTaskStillAlive() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "snapshot-precise-test"),
                ParticipantSnapshot.class);
        ParticipantSnapshotRecorderImpl recorder = new ParticipantSnapshotRecorderImpl(mapper);

        BpmTaskFacade facade = mock(BpmTaskFacade.class);
        // 会签同节点：甲(t-a, 本次处理)、乙(t-b, 任务仍存活)、丙(t-c, 任务已被取消)
        when(facade.getTask("t-b")).thenReturn(Optional.of(new BpmTaskDTO()));
        when(facade.getTask("t-c")).thenReturn(Optional.empty());
        recorder.setTaskFacadeProvider(providerOf(facade));
        when(mapper.selectList(any())).thenReturn(List.of(
                row(2L, "t-b", "yi"), row(3L, "t-c", "bing")));

        recorder.settle("pi-1", "node_cosign", "t-a", "jia", "APPROVE", 0L);

        ArgumentCaptor<LambdaUpdateWrapper<ParticipantSnapshot>> wrappers =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), wrappers.capture());

        // MyBatis-Plus 的 WHERE 条件参数在 SQL 段生成（getSqlSegment）时惰性登记：
        // 先物化两段 SQL，再对参数表断言，避免依赖尚未生成的中间状态。
        for (LambdaUpdateWrapper<ParticipantSnapshot> w : wrappers.getAllValues()) {
            w.getSqlSegment();
            w.getSqlSet();
        }
        // 仅对任务已消失的候选按主键失效：参数表含行主键3L且置 INVALIDATED
        List<LambdaUpdateWrapper<ParticipantSnapshot>> invalidations = wrappers.getAllValues().stream()
                .filter(w -> w.getParamNameValuePairs().containsValue("INVALIDATED")).toList();
        assertThat(invalidations).hasSize(1);
        assertThat(invalidations.get(0).getParamNameValuePairs().values()).contains(3L);
        // 存活任务候选（行主键2L）从未被失效
        assertThat(wrappers.getAllValues().stream()
                .filter(w -> w.getParamNameValuePairs().containsValue("INVALIDATED"))
                .flatMap(w -> w.getParamNameValuePairs().values().stream())
                .toList()).doesNotContain(2L);
        // 本次处理者按 taskId+participantId 结算为 HANDLED
        assertThat(wrappers.getAllValues().stream()
                .anyMatch(w -> w.getSqlSegment().contains("task_id")
                        && w.getParamNameValuePairs().containsValue("HANDLED"))).isTrue();
    }

    @Test
    void settleFallsBackToLegacyWhenFacadeAbsent() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "snapshot-legacy-test"),
                ParticipantSnapshot.class);
        ParticipantSnapshotRecorderImpl recorder = new ParticipantSnapshotRecorderImpl(mapper);

        recorder.settle("pi-2", "node_shared", "task-current", "user-1", "APPROVE", 0L);

        ArgumentCaptor<LambdaUpdateWrapper<ParticipantSnapshot>> wrappers =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), wrappers.capture());
        assertThat(wrappers.getAllValues().get(0).getSqlSegment())
                .contains("process_instance_id", "participant_status")
                .doesNotContain("task_id");
    }
}
