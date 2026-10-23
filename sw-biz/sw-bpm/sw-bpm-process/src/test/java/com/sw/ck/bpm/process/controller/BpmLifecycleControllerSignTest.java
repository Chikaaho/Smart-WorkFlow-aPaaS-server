package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmSignRecord;
import com.sw.ck.bpm.process.mapper.BpmSignRecordMapper;
import com.sw.ck.bpm.process.service.ApprovalLifecycleService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BpmLifecycleControllerSignTest {
    private final BpmSignRecordMapper mapper = mock(BpmSignRecordMapper.class);
    private final BpmTaskFacade taskFacade = mock(BpmTaskFacade.class);
    private final BpmLifecycleController controller = new BpmLifecycleController(
            mock(ApprovalLifecycleService.class), mapper, mock(BpmInstanceService.class), taskFacade);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "lifecycle-sign-test"),
                BpmSignRecord.class);
    }

    @AfterEach
    void clear() {
        LoginUserHolder.clear();
    }

    @Test
    void pendingSignsShouldExposePositionAndSerialGate() {
        LoginUser actor = new LoginUser();
        actor.setUserId(8L);
        actor.setTenantId(100L);
        LoginUserHolder.set(actor);
        BpmSignRecord first = record(1L, 0);
        BpmSignRecord second = record(2L, 1);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("task-1");
        task.setName("部门审批");
        task.setProcessDefinitionKey("leave");
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(task));

        List<Map<String, Object>> result = controller.pendingSigns().getData();
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("signPosition", "AFTER")
                .containsEntry("serialBlocked", false).containsEntry("taskName", "部门审批");
        assertThat(result.get(1)).containsEntry("signPosition", "AFTER")
                .containsEntry("serialBlocked", true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<BpmSignRecord>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(2)).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues()).allSatisfy(wrapper -> {
            LambdaQueryWrapper<BpmSignRecord> query = (LambdaQueryWrapper<BpmSignRecord>) wrapper;
            assertThat(query.getSqlSegment()).contains("tenant_id");
            assertThat(query.getParamNameValuePairs().values()).contains(100L);
        });
    }

    @Test
    void signAndCommunicationEndpointsRequireAuthenticationWithoutPagePermission() throws Exception {
        for (String methodName : List.of("pendingSigns", "expressSign", "replyCommunication")) {
            PreAuthorize annotation = BpmLifecycleController.class
                    .getDeclaredMethod(methodName,
                            methodName.equals("pendingSigns") ? new Class<?>[0]
                                    : new Class<?>[]{Long.class, ApprovalActionRequest.class})
                    .getAnnotation(PreAuthorize.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("isAuthenticated()");
        }
    }

    private BpmSignRecord record(long id, int seq) {
        BpmSignRecord record = new BpmSignRecord();
        record.setId(id);
        record.setTaskId("task-1");
        record.setProcessInstanceId("instance-1");
        record.setNodeKey("approve");
        record.setSignType("ADD_SIGN");
        record.setModeType("SERIAL");
        record.setSeqNo(seq);
        record.setParticipantId(8L);
        record.setTenantId(100L);
        record.setSignStatus("PENDING");
        record.setDetail("{\"signPosition\":\"AFTER\",\"comment\":\"请确认\"}");
        return record;
    }
}
