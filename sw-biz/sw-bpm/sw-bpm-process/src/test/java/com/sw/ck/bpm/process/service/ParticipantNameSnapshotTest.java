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

    @Test
    @DisplayName("I4 G1a：任务级解析按 taskId 逐一对应，动态并行多分支不合并为同一人")
    void resolveTaskAssignees_shouldKeyByTaskId() {
        ParticipantSnapshot branchA = new ParticipantSnapshot();
        branchA.setProcessInstanceId("pi-x");
        branchA.setNodeKey("dyn");
        branchA.setTaskId("task-a");
        branchA.setParticipantId("101");
        branchA.setParticipantStatus("HANDLED");
        ParticipantSnapshot branchB = new ParticipantSnapshot();
        branchB.setProcessInstanceId("pi-x");
        branchB.setNodeKey("dyn");
        branchB.setTaskId("task-b");
        branchB.setParticipantId("102");
        branchB.setParticipantStatus("PENDING");
        when(snapshotMapper.selectList(any())).thenReturn(List.of(branchA, branchB));

        ParticipantNameService service = new ParticipantNameService(snapshotMapper, userQueryFacade);
        Map<String, Long> byTask = service.resolveTaskAssignees("pi-x");

        assertThat(byTask).containsEntry("task-a", 101L).containsEntry("task-b", 102L);
        // 与节点级解析对照：同一 node_key 两分支在节点级必然坍缩为一个人
        Map<String, Long> byNode = service.resolveNodeAssignees("pi-x");
        assertThat(byNode).containsKey("dyn");
    }

    @Test
    @DisplayName("I4 G1a：同任务多轮快照按 HANDLED>PENDING 取一；异常回退空 Map")
    void resolveTaskAssignees_shouldRankAndDegrade() {
        ParticipantSnapshot pending = new ParticipantSnapshot();
        pending.setTaskId("task-1");
        pending.setParticipantId("101");
        pending.setParticipantStatus("PENDING");
        ParticipantSnapshot handled = new ParticipantSnapshot();
        handled.setTaskId("task-1");
        handled.setParticipantId("102");
        handled.setParticipantStatus("HANDLED");
        when(snapshotMapper.selectList(any())).thenReturn(List.of(pending, handled));

        ParticipantNameService service = new ParticipantNameService(snapshotMapper, userQueryFacade);
        assertThat(service.resolveTaskAssignees("pi-1")).containsEntry("task-1", 102L);

        when(snapshotMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        assertThat(service.resolveTaskAssignees("pi-1")).isEmpty();
    }
}
