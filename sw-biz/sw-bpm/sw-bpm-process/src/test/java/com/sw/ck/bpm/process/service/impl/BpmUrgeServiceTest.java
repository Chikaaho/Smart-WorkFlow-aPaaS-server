package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.UrgeRespDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.UrgeRecord;
import com.sw.ck.bpm.process.mapper.UrgeRecordMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 催办服务单测（v0.0.2 OA）。
 * 覆盖：非发起人拒绝、结束实例拒绝、10 分钟冷却、成功受理发送通知并留痕。
 */
@ExtendWith(MockitoExtension.class)
class BpmUrgeServiceTest {

    @Mock
    private BpmInstanceService bpmInstanceService;
    @Mock
    private BpmTaskFacade bpmTaskFacade;
    @Mock
    private UrgeRecordMapper urgeRecordMapper;
    @Mock
    private NotifyFacade notifyFacade;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private BpmUrgeServiceImpl urgeService;

    @BeforeEach
    void setUp() {
        urgeService = new BpmUrgeServiceImpl(bpmInstanceService, bpmTaskFacade,
                urgeRecordMapper, notifyFacade, jdbcTemplate);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(7L);
        loginUser.setTenantId(1L);
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private BpmInstance instance(Long id, Long initiator, String status, String piId) {
        BpmInstance inst = new BpmInstance();
        inst.setId(id);
        inst.setInitiatorId(initiator);
        inst.setStatus(status);
        inst.setProcessInstanceId(piId);
        return inst;
    }

    @Test
    @DisplayName("非发起人催办 → FORBIDDEN，不留催办记录")
    void urgeByNonInitiatorForbidden() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 8L, "RUNNING", "pi-1"));
        assertThatThrownBy(() -> urgeService.urge(1L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("仅发起人");
        verify(urgeRecordMapper, never()).insert(any(UrgeRecord.class));
    }

    @Test
    @DisplayName("实例已结束 → REJECTED 并留痕，不发送通知")
    void urgeEndedInstanceRejected() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 7L, "APPROVED", "pi-1"));
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        UrgeRespDTO resp = urgeService.urge(1L);
        assertThat(resp.getResult()).isEqualTo("REJECTED");
        assertThat(resp.getDetail()).contains("已结束");
        verify(notifyFacade, never()).send(any(NotifySendRequest.class));
        ArgumentCaptor<UrgeRecord> captor = ArgumentCaptor.forClass(UrgeRecord.class);
        verify(urgeRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("10 分钟冷却内重复催办 → COOLDOWN，不重复发送")
    void urgeWithinCooldownReturnsCooldown() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 7L, "RUNNING", "pi-1"));
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        UrgeRecord lastAccepted = new UrgeRecord();
        lastAccepted.setProcessInstanceId("pi-1");
        lastAccepted.setResult("ACCEPTED");
        lastAccepted.setCreateTime(LocalDateTime.now().minusMinutes(5));
        when(urgeRecordMapper.selectList(any())).thenReturn(List.of(lastAccepted));

        UrgeRespDTO resp = urgeService.urge(1L);
        assertThat(resp.getResult()).isEqualTo("COOLDOWN");
        assertThat(resp.getDetail()).contains("冷却");
        verify(notifyFacade, never()).send(any(NotifySendRequest.class));
    }

    @Test
    @DisplayName("运行中且有活动任务 → ACCEPTED：通知当前待办人并落 ACCEPTED 记录")
    void urgeAcceptedNotifiesAssignee() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 7L, "RUNNING", "pi-1"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(urgeRecordMapper.selectList(any())).thenReturn(List.of());
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("t-1");
        task.setAssignee("9");
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(java.util.Optional.of(List.of(task)));

        when(notifyFacade.send(any(NotifySendRequest.class))).thenReturn(
                java.util.Optional.of(NotifySendResult.builder()
                        .channel(com.sw.ck.notify.api.NotifyChannel.IN_APP)
                        .status("SUCCESS")
                        .build()));

        UrgeRespDTO resp = urgeService.urge(1L);
        assertThat(resp.getResult()).isEqualTo("ACCEPTED");
        verify(notifyFacade, times(1)).send(any(NotifySendRequest.class));
        ArgumentCaptor<UrgeRecord> captor = ArgumentCaptor.forClass(UrgeRecord.class);
        verify(urgeRecordMapper).insert(captor.capture());
        UrgeRecord record = captor.getValue();
        assertThat(record.getResult()).isEqualTo("ACCEPTED");
        assertThat(record.getTargetUserId()).isEqualTo(9L);
        assertThat(record.getCooldownKey()).isNotBlank();
    }

    @Test
    @DisplayName("带留言催办 → 通知内容与留痕明细均包含留言")
    void urgeWithMessageIncludesMessage() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 7L, "RUNNING", "pi-1"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(urgeRecordMapper.selectList(any())).thenReturn(List.of());
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("t-1");
        task.setAssignee("9");
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(java.util.Optional.of(List.of(task)));
        when(notifyFacade.send(any(NotifySendRequest.class))).thenReturn(
                java.util.Optional.of(NotifySendResult.builder()
                        .channel(com.sw.ck.notify.api.NotifyChannel.IN_APP)
                        .status("SUCCESS")
                        .build()));

        UrgeRespDTO resp = urgeService.urge(1L, "请尽快处理报销单");
        assertThat(resp.getResult()).isEqualTo("ACCEPTED");
        ArgumentCaptor<NotifySendRequest> notifyCaptor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(notifyFacade).send(notifyCaptor.capture());
        assertThat(notifyCaptor.getValue().getContent()).contains("请尽快处理报销单");
        ArgumentCaptor<UrgeRecord> captor = ArgumentCaptor.forClass(UrgeRecord.class);
        verify(urgeRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).contains("请尽快处理报销单");
    }

    @Test
    @DisplayName("留言超过 200 字 → 参数错误，不发送通知不留痕")
    void urgeWithOverlongMessageRejected() {
        // 留言校验先于实例读取，不产生任何副作用
        assertThatThrownBy(() -> urgeService.urge(1L, "留".repeat(201)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("200");
        verify(notifyFacade, never()).send(any(NotifySendRequest.class));
        verify(urgeRecordMapper, never()).insert(any(UrgeRecord.class));
    }

    @Test
    @DisplayName("空白留言 → 与无留言同语义，通知内容不含留言段")
    void urgeWithBlankMessageSameAsLegacy() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 7L, "RUNNING", "pi-1"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(urgeRecordMapper.selectList(any())).thenReturn(List.of());
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("t-1");
        task.setAssignee("9");
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(java.util.Optional.of(List.of(task)));
        when(notifyFacade.send(any(NotifySendRequest.class))).thenReturn(
                java.util.Optional.of(NotifySendResult.builder()
                        .channel(com.sw.ck.notify.api.NotifyChannel.IN_APP)
                        .status("SUCCESS")
                        .build()));

        UrgeRespDTO resp = urgeService.urge(1L, "   ");
        assertThat(resp.getResult()).isEqualTo("ACCEPTED");
        ArgumentCaptor<NotifySendRequest> notifyCaptor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(notifyFacade).send(notifyCaptor.capture());
        assertThat(notifyCaptor.getValue().getContent()).doesNotContain("留言");
    }

    // ==================== 催办接收人扩展（direction-ui-completion-020） ====================

    private BpmTaskDTO task(String id, String assignee, List<String> candidates) {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId(id);
        task.setAssignee(assignee);
        task.setCandidateUserIds(candidates);
        return task;
    }

    private void acceptedNotification() {
        when(notifyFacade.send(any(NotifySendRequest.class))).thenReturn(
                java.util.Optional.of(NotifySendResult.builder()
                        .channel(com.sw.ck.notify.api.NotifyChannel.IN_APP)
                        .status("SUCCESS")
                        .build()));
    }

    @Test
    @DisplayName("当前待办参与人也可催办（非仅发起人）")
    void activeParticipantCanUrge() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 8L, "RUNNING", "pi-1"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(urgeRecordMapper.selectList(any())).thenReturn(List.of());
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(
                java.util.Optional.of(List.of(task("t-1", "7", null))));
        acceptedNotification();

        UrgeRespDTO resp = urgeService.urge(1L, null, null);
        assertThat(resp.getResult()).isEqualTo("ACCEPTED");
        verify(notifyFacade).send(any(NotifySendRequest.class));
    }

    @Test
    @DisplayName("非发起人且非待办参与人 → FORBIDDEN，不发送")
    void outsiderCannotUrge() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 8L, "RUNNING", "pi-1"));
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(
                java.util.Optional.of(List.of(task("t-1", "9", List.of("10")))));

        assertThatThrownBy(() -> urgeService.urge(1L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("待办参与人");
        verify(notifyFacade, never()).send(any(NotifySendRequest.class));
        verify(urgeRecordMapper, never()).insert(any(UrgeRecord.class));
    }

    @Test
    @DisplayName("接收人限定为合法子集：只通知所选待办人")
    void urgeToSelectedRecipientsOnly() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 7L, "RUNNING", "pi-1"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(urgeRecordMapper.selectList(any())).thenReturn(List.of());
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(
                java.util.Optional.of(List.of(task("t-1", "9", null), task("t-2", "10", null))));
        acceptedNotification();

        UrgeRespDTO resp = urgeService.urge(1L, "请优先处理", List.of(10L));
        assertThat(resp.getResult()).isEqualTo("ACCEPTED");

        ArgumentCaptor<NotifySendRequest> captor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(notifyFacade, times(1)).send(captor.capture());
        assertThat(captor.getValue().getRecipientId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("接收人越界（非当前实例待办人）→ REJECTED 留痕，不发送")
    void urgeRejectsRecipientOutsideLegalScope() {
        when(bpmInstanceService.getById(1L)).thenReturn(instance(1L, 7L, "RUNNING", "pi-1"));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(urgeRecordMapper.selectList(any())).thenReturn(List.of());
        when(bpmTaskFacade.queryByProcessInstance("pi-1")).thenReturn(
                java.util.Optional.of(List.of(task("t-1", "9", null))));

        UrgeRespDTO resp = urgeService.urge(1L, null, List.of(9999L));
        assertThat(resp.getResult()).isEqualTo("REJECTED");
        assertThat(resp.getDetail()).contains("合法待办范围");
        verify(notifyFacade, never()).send(any(NotifySendRequest.class));
        ArgumentCaptor<UrgeRecord> captor = ArgumentCaptor.forClass(UrgeRecord.class);
        verify(urgeRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo("REJECTED");
    }
}
