package com.sw.ck.bpm.process.config;

import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;
import com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P63 动态分支端口 v2 行为：对象身份独立分支、executionId 轮次（恢复复用/退回新轮次）、
 * 旧轮次收口（SUPERSEDED_BY_ROUND）、任务绑定按 task_id 优先、负向结算只关最新轮次。
 */
class P63DynamicBranchPortV2Test {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.sw.ck.bpm.process.entity.DynamicBranchSnapshot.class);
    }

    private List<DynamicBranchPort.BranchCandidateV2> candidates() {
        return List.of(
                new DynamicBranchPort.BranchCandidateV2("7", "30", null, "[{\"rowId\":\"r1\"}]"),
                new DynamicBranchPort.BranchCandidateV2("8", "30", null, "[{\"rowId\":\"r2\"}]"),
                new DynamicBranchPort.BranchCandidateV2("9", null, "LEADER_MISSING", "[]"));
    }

    @Test
    @DisplayName("同负责人不同部门保持独立分支（v2 不合并）；无效对象落 CANCELED；round=0/execution 绑定")
    void freezeRoundKeepsObjectBranches() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(), List.of());
        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);

        List<DynamicBranchPort.FrozenBranchV2> frozen = port.freezeRound("1", "pi-1", "dyn",
                "exec-1", "FORM_FIELD", "dept_list", "ALL", "DEPT", candidates()).orElseThrow();

        assertThat(frozen).as("部门 7/8 同负责人仍为两条独立分支").hasSize(2);
        assertThat(frozen).extracting(DynamicBranchPort.FrozenBranchV2::objectId)
                .containsExactly("7", "8");
        assertThat(frozen).extracting(DynamicBranchPort.FrozenBranchV2::assigneeId)
                .containsExactly("30", "30");

        ArgumentCaptor<DynamicBranchSnapshot> rows = ArgumentCaptor.forClass(DynamicBranchSnapshot.class);
        verify(mapper, times(3)).insert(rows.capture());
        DynamicBranchSnapshot first = rows.getAllValues().get(0);
        assertThat(first.getSemanticVersion()).isEqualTo(2);
        assertThat(first.getRoundNo()).isEqualTo(0L);
        assertThat(first.getExecutionId()).isEqualTo("exec-1");
        assertThat(first.getObjectType()).isEqualTo("DEPT");
        assertThat(first.getObjectId()).isEqualTo("7");
        assertThat(first.getLeaderId()).isEqualTo(30L);
        assertThat(first.getSourceRefs()).contains("r1");
        DynamicBranchSnapshot canceled = rows.getAllValues().get(2);
        assertThat(canceled.getStatus()).isEqualTo("CANCELED");
        assertThat(canceled.getCancelReason()).isEqualTo("LEADER_MISSING");
        assertThat(canceled.getRoundNo()).isEqualTo(0L);
    }

    @Test
    @DisplayName("同 executionId 恢复/重试：复用既有轮次快照，不重算不追加")
    void sameExecutionReusesRound() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        DynamicBranchSnapshot existing = new DynamicBranchSnapshot();
        existing.setBranchIndex(0);
        existing.setSemanticVersion(2);
        existing.setRoundNo(0L);
        existing.setExecutionId("exec-1");
        existing.setObjectType("DEPT");
        existing.setObjectId("7");
        existing.setLeaderId(30L);
        existing.setStatus("START");
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);

        List<DynamicBranchPort.FrozenBranchV2> frozen = port.freezeRound("1", "pi-1", "dyn",
                "exec-1", "FORM_FIELD", "dept_list", "ALL", "DEPT",
                List.of(new DynamicBranchPort.BranchCandidateV2("99", "99", null, "[]"))).orElseThrow();

        assertThat(frozen).hasSize(1);
        assertThat(frozen.get(0).objectId()).as("冻结权威：不采纳新候选").isEqualTo("7");
        verify(mapper, never()).insert(any(DynamicBranchSnapshot.class));
        verify(mapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    @DisplayName("新 executionId（退回重入）：开启新轮次并把旧轮次 START 收口为 SUPERSEDED_BY_ROUND")
    void newExecutionStartsNewRoundAndSupersedesOld() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        DynamicBranchSnapshot oldRound = new DynamicBranchSnapshot();
        oldRound.setBranchIndex(0);
        oldRound.setSemanticVersion(2);
        oldRound.setRoundNo(0L);
        oldRound.setExecutionId("exec-1");
        oldRound.setLeaderId(30L);
        oldRound.setStatus("START");
        when(mapper.selectList(any())).thenReturn(List.of(), List.of(oldRound));
        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);

        List<DynamicBranchPort.FrozenBranchV2> frozen = port.freezeRound("1", "pi-1", "dyn",
                "exec-2", "FORM_FIELD", "dept_list", "ALL", "DEPT",
                List.of(new DynamicBranchPort.BranchCandidateV2("7", "31", null, "[]"))).orElseThrow();

        assertThat(frozen).hasSize(1);
        ArgumentCaptor<DynamicBranchSnapshot> rows = ArgumentCaptor.forClass(DynamicBranchSnapshot.class);
        verify(mapper, times(1)).insert(rows.capture());
        assertThat(rows.getValue().getRoundNo()).as("新轮次 = 旧最大 + 1").isEqualTo(1L);
        assertThat(rows.getValue().getExecutionId()).isEqualTo("exec-2");
        verify(mapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    @DisplayName("recordAction：v2 首次回调按办理人绑定未关联分支；重复回调按 task_id 命中且幂等")
    void recordActionBindsTaskThenIdempotent() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        DynamicBranchSnapshot unbound = new DynamicBranchSnapshot();
        unbound.setId(11L);
        unbound.setSemanticVersion(2);
        unbound.setRoundNo(0L);
        unbound.setLeaderId(30L);
        unbound.setStatus("START");
        // 第一次调用：按 task_id 查无 → 按办理人+未绑定查到
        when(mapper.selectOne(any())).thenReturn(null, unbound);

        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);
        Optional<MutationOutcome> first = port.recordAction("1", "pi-1", "dyn", "30", "task-1",
                "START", null);
        assertThat(first).contains(MutationOutcome.APPLIED);
        verify(mapper, times(1)).updateById(org.mockito.ArgumentMatchers.<DynamicBranchSnapshot>argThat(patch ->
                "task-1".equals(((DynamicBranchSnapshot) patch).getTaskId())));

        // 第二次回调（同 task）：task_id 命中同一行
        DynamicBranchSnapshot bound = new DynamicBranchSnapshot();
        bound.setId(11L);
        bound.setSemanticVersion(2);
        bound.setLeaderId(30L);
        bound.setTaskId("task-1");
        bound.setStatus("START");
        when(mapper.selectOne(any())).thenReturn(bound);
        Optional<MutationOutcome> second = port.recordAction("1", "pi-1", "dyn", "30", "task-1",
                "APPROVE", null);
        assertThat(second).contains(MutationOutcome.APPLIED);
    }

    @Test
    @DisplayName("closeRemaining：存在 v2 行时只关闭最新轮次")
    void closeRemainingScopesLatestRound() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        DynamicBranchSnapshot latest = new DynamicBranchSnapshot();
        latest.setRoundNo(2L);
        latest.setSemanticVersion(2);
        when(mapper.selectOne(any())).thenReturn(latest);
        when(mapper.update(any(), any())).thenReturn(1);
        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);

        Optional<MutationOutcome> outcome = port.closeRemaining("1", "pi-1", "dyn", "VETO:neg");
        assertThat(outcome).contains(MutationOutcome.APPLIED);
        verify(mapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), any());
    }
}
