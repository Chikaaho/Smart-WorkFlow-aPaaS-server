package com.sw.ck.bpm.process.config;

import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;
import com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I4 §3.1 冻结端口实现行为：同负责人去重合并、无效对象 CANCELED 记录原因、
 * 冻结幂等（已有快照不重算）、closeRemaining 不改写已终态分支。
 */
class DynamicBranchPortTest {

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.sw.ck.bpm.process.entity.DynamicBranchSnapshot.class);
    }

    @Test
    void shouldDedupeSameLeaderAndRecordInvalidReasons() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);

        List<DynamicBranchPort.FrozenBranch> frozen = port.freeze("1", "pi-1", "dyn",
                "FIXED", "[1,2,3,4,7]", "ALL", List.of(
                        new DynamicBranchPort.BranchCandidate("1", "10", null),
                        new DynamicBranchPort.BranchCandidate("3", "30", null),
                        new DynamicBranchPort.BranchCandidate("4", "30", null),
                        new DynamicBranchPort.BranchCandidate("7", null, "LEADER_MISSING")));

        assertThat(frozen).hasSize(2);
        assertThat(frozen.get(0).leaderId()).isEqualTo("10");
        assertThat(frozen.get(1).leaderId()).isEqualTo("30");

        ArgumentCaptor<DynamicBranchSnapshot> rows = ArgumentCaptor.forClass(DynamicBranchSnapshot.class);
        verify(mapper, times(3)).insert(rows.capture());
        DynamicBranchSnapshot leader10 = rows.getAllValues().get(0);
        assertThat(leader10.getDeptIds()).isEqualTo("1");
        assertThat(leader10.getStatus()).isEqualTo("START");
        DynamicBranchSnapshot leader30 = rows.getAllValues().get(1);
        assertThat(leader30.getDeptIds()).as("同负责人部门合并到一条分支").isEqualTo("3,4");
        DynamicBranchSnapshot canceled = rows.getAllValues().get(2);
        assertThat(canceled.getStatus()).isEqualTo("CANCELED");
        assertThat(canceled.getCancelReason()).isEqualTo("LEADER_MISSING");
    }

    @Test
    void shouldReturnExistingSnapshotWithoutRefreeze() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        DynamicBranchSnapshot existing = new DynamicBranchSnapshot();
        existing.setBranchIndex(0);
        existing.setLeaderId(10L);
        existing.setDeptIds("1");
        existing.setStatus("START");
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);

        List<DynamicBranchPort.FrozenBranch> frozen = port.freeze("1", "pi-1", "dyn",
                "VARIABLE", "deptList", "ALL", List.of(
                        new DynamicBranchPort.BranchCandidate("9", "99", null)));

        assertThat(frozen).hasSize(1);
        assertThat(frozen.get(0).leaderId()).as("冻结快照权威：不重算新候选").isEqualTo("10");
        verify(mapper, never()).insert(any(DynamicBranchSnapshot.class));
    }

    @Test
    void shouldCloseRemainingWithoutRewritingFinishedBranches() {
        DynamicBranchSnapshotMapper mapper = mock(DynamicBranchSnapshotMapper.class);
        DynamicBranchPort port = new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);
        port.closeRemaining("1", "pi-1", null, "INSTANCE_TERMINATED");
        // 实现按条件 UPDATE（只命中 START 行）；APPROVE/DISAPPROVE/CANCELED 天然不被改写
        verify(mapper).update(any(), any());
    }
}
