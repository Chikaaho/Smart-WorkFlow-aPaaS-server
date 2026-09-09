package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.bpm.process.service.impl.ParticipantSnapshotRecorderImpl;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I1 参与人展示名冻结与展示链测试（历史流程身份不被改写）。
 * <p>
 * 快照记录冻结姓名；展示链（ParticipantNameService）快照名优先、
 * 缺失回落实时查询、快照查询异常降级不阻断。
 * </p>
 */
@DisplayName("I1 参与人展示名冻结与快照优先解析")
@ExtendWith(MockitoExtension.class)
class ParticipantNameSnapshotTest {

    @Mock
    private ParticipantSnapshotMapper snapshotMapper;

    @Mock
    private UserQueryFacade userQueryFacade;

    @Test
    @DisplayName("record 重载冻结参与人姓名；旧 record 兼容路径姓名为 null")
    void recorder_shouldFreezeDisplayNames() {
        ParticipantSnapshotRecorderImpl recorder = new ParticipantSnapshotRecorderImpl(snapshotMapper);

        recorder.record("pi-1", "node-1", "task-1",
                List.of("101", "102"), Map.of("101", "张三"), 1L);

        ArgumentCaptor<ParticipantSnapshot> captor = ArgumentCaptor.forClass(ParticipantSnapshot.class);
        verify(snapshotMapper, times(2)).insert(captor.capture());
        List<ParticipantSnapshot> rows = captor.getAllValues();
        assertThat(rows).extracting(ParticipantSnapshot::getParticipantId)
                .containsExactly("101", "102");
        assertThat(rows.get(0).getParticipantName()).isEqualTo("张三");
        assertThat(rows.get(0).getParticipantStatus()).isEqualTo("PENDING");
        assertThat(rows.get(1).getParticipantName()).isNull();
    }

    @Test
    @DisplayName("展示链：快照名优先，缺失 ID 回落实时查询")
    void participantNameService_shouldPreferSnapshotThenFallback() {
        ParticipantSnapshot frozen = new ParticipantSnapshot();
        frozen.setProcessInstanceId("pi-1");
        frozen.setTaskId("task-1");
        frozen.setParticipantId("101");
        frozen.setParticipantName("冻结名");
        frozen.setParticipantStatus("HANDLED");
        when(snapshotMapper.selectList(any())).thenReturn(List.of(frozen));
        when(userQueryFacade.getUserDisplayNames(java.util.Set.of(102L))).thenReturn(Map.of(102L, "实时名"));

        ParticipantNameService service = new ParticipantNameService(snapshotMapper, userQueryFacade);
        Map<Long, String> names = service.resolveDisplayNames("pi-1", List.of(101L, 102L));

        assertThat(names).containsEntry(101L, "冻结名").containsEntry(102L, "实时名");
    }

    @Test
    @DisplayName("快照查询异常时整批回落实时查询，不阻断调用方")
    void participantNameService_shouldDegradeOnSnapshotFailure() {
        when(snapshotMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        when(userQueryFacade.getUserDisplayNames(java.util.Set.of(101L))).thenReturn(Map.of(101L, "实时名"));

        ParticipantNameService service = new ParticipantNameService(snapshotMapper, userQueryFacade);
        Map<Long, String> names = service.resolveDisplayNames("pi-1", List.of(101L));

        assertThat(names).containsEntry(101L, "实时名");
    }
}
