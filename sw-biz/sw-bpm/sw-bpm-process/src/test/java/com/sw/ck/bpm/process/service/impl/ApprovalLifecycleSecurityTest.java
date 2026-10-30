package com.sw.ck.bpm.process.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmCommunication;
import com.sw.ck.bpm.process.entity.BpmSignRecord;
import com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper;
import com.sw.ck.bpm.process.mapper.BpmCommunicationMapper;
import com.sw.ck.bpm.process.mapper.BpmConsensusVoteMapper;
import com.sw.ck.bpm.process.mapper.BpmSignRecordMapper;
import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 加签表态与沟通回复的对象权限、租户隔离和串行顺序负向回归。 */
class ApprovalLifecycleSecurityTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "approval-security-test"),
                BpmSignRecord.class);
    }

    private final BpmSignRecordMapper signRecordMapper = mock(BpmSignRecordMapper.class);
    private final BpmCommunicationMapper communicationMapper = mock(BpmCommunicationMapper.class);
    private final BpmTaskFacade taskFacade = mock(BpmTaskFacade.class);
    private final ApprovalActionService approvalActionService = mock(ApprovalActionService.class);
    private final ObjectProvider<TaskActionService> taskActions = provider(TaskActionService.class);
    private final ApprovalLifecycleServiceImpl service = new ApprovalLifecycleServiceImpl(
            taskFacade,
            mock(BpmInstanceService.class),
            mock(BpmProcessDefService.class),
            approvalActionService,
            mock(BpmAuthorizeRuleMapper.class),
            communicationMapper,
            signRecordMapper,
            mock(BpmTaskDeadlineMapper.class),
            mock(BpmConsensusVoteMapper.class),
            mock(ParticipantSnapshotMapper.class),
            provider(UserQueryFacade.class),
            taskActions,
            provider(DynamicBranchPort.class),
            mock(DomainEventPublisher.class),
            new ObjectMapper());

    @BeforeEach
    void authenticate() {
        LoginUser actor = new LoginUser();
        actor.setUserId(8L);
        actor.setTenantId(100L);
        LoginUserHolder.set(actor);
    }

    @AfterEach
    void clearActor() {
        LoginUserHolder.clear();
    }

    @Test
    void expressSignRejectsNonParticipantWithoutWriting() {
        BpmSignRecord record = pendingSign(9L, 100L);
        when(signRecordMapper.selectById(1L)).thenReturn(record);

        BaseException error = assertThrows(BaseException.class, () -> service.expressSign(1L, approve()));

        assertThat(error.getCode()).isEqualTo(403);
        assertThat(error.getMessage()).contains("本人");
        verify(signRecordMapper, never()).updateById(any(BpmSignRecord.class));
    }

    @Test
    void expressSignRejectsCrossTenantRecordWithoutWriting() {
        BpmSignRecord record = pendingSign(8L, 200L);
        when(signRecordMapper.selectById(1L)).thenReturn(record);

        BaseException error = assertThrows(BaseException.class, () -> service.expressSign(1L, approve()));

        assertThat(error.getCode()).isEqualTo(403);
        assertThat(error.getMessage()).contains("当前租户");
        verify(signRecordMapper, never()).updateById(any(BpmSignRecord.class));
    }

    @Test
    void expressSignRejectsSerialParticipantUntilEarlierPendingRecordSettles() {
        BpmSignRecord record = pendingSign(8L, 100L);
        record.setSeqNo(1);
        when(signRecordMapper.selectById(1L)).thenReturn(record);
        when(signRecordMapper.selectCount(any())).thenReturn(1L);

        BaseException error = assertThrows(BaseException.class, () -> service.expressSign(1L, approve()));

        assertThat(error.getCode()).isEqualTo(2400);
        assertThat(error.getMessage()).contains("串行加签");
        verify(signRecordMapper, never()).updateById(any(BpmSignRecord.class));
    }

    @Test
    void replyCommunicationRejectsNonReceiverWithoutWriting() {
        BpmCommunication communication = pendingCommunication(9L, 100L);
        when(communicationMapper.selectById(1L)).thenReturn(communication);

        BaseException error = assertThrows(BaseException.class,
                () -> service.replyCommunication(1L, approve()));

        assertThat(error.getCode()).isEqualTo(403);
        assertThat(error.getMessage()).contains("接收人");
        verify(communicationMapper, never()).updateById(any(BpmCommunication.class));
    }

    @Test
    void replyCommunicationRejectsCrossTenantRecordWithoutWriting() {
        BpmCommunication communication = pendingCommunication(8L, 200L);
        when(communicationMapper.selectById(1L)).thenReturn(communication);

        BaseException error = assertThrows(BaseException.class,
                () -> service.replyCommunication(1L, approve()));

        assertThat(error.getCode()).isEqualTo(403);
        assertThat(error.getMessage()).contains("当前租户");
        verify(communicationMapper, never()).updateById(any(BpmCommunication.class));
    }

    @Test
    void expressSignRejectsOptimisticUpdateMissWithoutLifecycleOrSettlement() {
        BpmSignRecord record = pendingSign(8L, 100L);
        record.setModeType("PARALLEL");
        record.setVersion(7L);
        when(signRecordMapper.selectById(1L)).thenReturn(record);
        when(signRecordMapper.updateById(any(BpmSignRecord.class))).thenReturn(0);

        BaseException error = assertThrows(BaseException.class, () -> service.expressSign(1L, approve()));

        assertThat(error.getCode()).isEqualTo(2400);
        assertThat(error.getMessage()).contains("状态已变化");
        verify(taskFacade, never()).getTask(any());
        verify(approvalActionService, never()).upsertDuplicate(any());
        verify(signRecordMapper, never()).selectList(any());
    }

    @Test
    void cancelSignRejectsOptimisticUpdateMissWithoutLifecycle() {
        BpmSignRecord record = pendingSign(8L, 100L);
        record.setVersion(7L);
        when(signRecordMapper.selectById(1L)).thenReturn(record);
        when(signRecordMapper.updateById(any(BpmSignRecord.class))).thenReturn(0);

        BaseException error = assertThrows(BaseException.class,
                () -> service.cancelSign(1L, approve()));

        assertThat(error.getCode()).isEqualTo(2400);
        assertThat(error.getMessage()).contains("状态已变化");
        verify(taskFacade, never()).getTask(any());
        verify(approvalActionService, never()).upsertDuplicate(any());
    }

    @Test
    void nullVersionLegacyRecordStillRejectsZeroRowUpdate() {
        BpmSignRecord record = pendingSign(8L, 100L);
        record.setModeType("PARALLEL");
        record.setVersion(null);
        when(signRecordMapper.selectById(1L)).thenReturn(record);
        when(signRecordMapper.updateById(any(BpmSignRecord.class))).thenReturn(0);

        BaseException error = assertThrows(BaseException.class, () -> service.expressSign(1L, approve()));

        assertThat(error.getCode()).isEqualTo(2400);
        verify(taskFacade, never()).getTask(any());
        verify(approvalActionService, never()).upsertDuplicate(any());
    }

    @Test
    void successfulExpressWithNoRemainingSignRowsDoesNotSettle() {
        BpmSignRecord record = pendingSign(8L, 100L);
        record.setModeType("PARALLEL");
        when(signRecordMapper.selectById(1L)).thenReturn(record);
        when(signRecordMapper.updateById(any(BpmSignRecord.class))).thenReturn(1);
        when(signRecordMapper.selectList(any())).thenReturn(List.of());
        when(taskFacade.getTask("task-1")).thenReturn(Optional.empty());

        service.expressSign(1L, approve());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<BpmSignRecord>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(signRecordMapper, times(1)).selectList(queryCaptor.capture());
        LambdaQueryWrapper<BpmSignRecord> query =
                (LambdaQueryWrapper<BpmSignRecord>) queryCaptor.getValue();
        assertThat(query.getSqlSegment()).contains("tenant_id");
        assertThat(query.getParamNameValuePairs().values()).contains(100L, "task-1");
    }

    @Test
    void objectScopedActionsRequireAuthentication() {
        LoginUserHolder.clear();

        assertThatThrownBy(() -> service.expressSign(1L, approve()))
                .isInstanceOf(BaseException.class)
                .extracting("code")
                .isEqualTo(401);
        assertThatThrownBy(() -> service.replyCommunication(1L, approve()))
                .isInstanceOf(BaseException.class)
                .extracting("code")
                .isEqualTo(401);
    }

    @Test
    void lifecycleActionsCannotBypassFrozenNodePolicy() {
        TaskActionService policy = new TaskActionService(
                null, null, null, null, null, null, new ObjectMapper(), null);
        when(taskActions.getIfAvailable()).thenReturn(policy);
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("task-1"); task.setAssignee("8");
        task.setNodeConfig(Map.of("allowedActions", List.of("APPROVE")));
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(task));
        for (ApprovalAction action : List.of(ApprovalAction.TRANSFER, ApprovalAction.DELEGATE,
                ApprovalAction.COMMUNICATE, ApprovalAction.ADD_SIGN)) {
            ApprovalActionRequest request = new ApprovalActionRequest(); request.setAction(action);
            assertThatThrownBy(() -> service.executeTaskAction("task-1", request))
                    .isInstanceOf(BaseException.class).hasMessageContaining("不允许");
        }
        verifyNoInteractions(signRecordMapper, communicationMapper, approvalActionService);
    }

    private BpmSignRecord pendingSign(Long participantId, Long tenantId) {
        BpmSignRecord record = new BpmSignRecord();
        record.setId(1L);
        record.setProcessInstanceId("instance-1");
        record.setTaskId("task-1");
        record.setNodeKey("approve");
        record.setSignType("ADD_SIGN");
        record.setModeType("SERIAL");
        record.setOperatorId(9L);
        record.setParticipantId(participantId);
        record.setSeqNo(1);
        record.setSignStatus("PENDING");
        record.setTenantId(tenantId);
        record.setDetail("{}");
        return record;
    }

    private BpmCommunication pendingCommunication(Long receiverId, Long tenantId) {
        BpmCommunication communication = new BpmCommunication();
        communication.setId(1L);
        communication.setProcessInstanceId("instance-1");
        communication.setTaskId("task-1");
        communication.setInitiatorId(9L);
        communication.setReceiverId(receiverId);
        communication.setStatus("PENDING");
        communication.setTenantId(tenantId);
        return communication;
    }

    private ApprovalActionRequest approve() {
        ApprovalActionRequest request = new ApprovalActionRequest();
        request.setAction(ApprovalAction.APPROVE);
        request.setMessage("已确认");
        return request;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(Class<T> ignored) {
        return mock(ObjectProvider.class);
    }
}
