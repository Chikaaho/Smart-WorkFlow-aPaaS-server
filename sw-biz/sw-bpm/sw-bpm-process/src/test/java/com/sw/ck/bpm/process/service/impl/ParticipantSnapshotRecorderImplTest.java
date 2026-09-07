package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ParticipantSnapshotRecorderImplTest {

    @Mock
    private ParticipantSnapshotMapper mapper;

    @Test
    void settleInvalidatesAllPendingConsensusChildrenAcrossTaskIds() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "participant-snapshot-test"),
                ParticipantSnapshot.class);
        ParticipantSnapshotRecorderImpl recorder = new ParticipantSnapshotRecorderImpl(mapper);

        recorder.settle("pi-1", "node_consensus", "task-current", "user-1", "APPROVE", 0L);

        ArgumentCaptor<LambdaUpdateWrapper<ParticipantSnapshot>> wrappers =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), wrappers.capture());
        String invalidatedWhere = wrappers.getAllValues().get(0).getSqlSegment();
        String handledWhere = wrappers.getAllValues().get(1).getSqlSegment();

        assertThat(invalidatedWhere).contains("process_instance_id", "node_key", "participant_status")
                .doesNotContain("task_id");
        assertThat(handledWhere).contains("process_instance_id", "node_key", "task_id", "participant_id");
    }
}
