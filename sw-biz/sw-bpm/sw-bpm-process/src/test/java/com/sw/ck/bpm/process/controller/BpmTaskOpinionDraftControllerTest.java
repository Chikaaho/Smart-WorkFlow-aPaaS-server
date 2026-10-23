package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmTaskOpinionDraft;
import com.sw.ck.bpm.process.mapper.BpmTaskOpinionDraftMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BpmTaskOpinionDraftControllerTest {
    private final BpmTaskFacade taskFacade = mock(BpmTaskFacade.class);
    private final BpmTaskOpinionDraftMapper mapper = mock(BpmTaskOpinionDraftMapper.class);
    private final BpmTaskOpinionDraftController controller =
            new BpmTaskOpinionDraftController(taskFacade, mapper, new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void clear() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("仅当前办理人可读取本人意见暂存")
    void get_shouldRestoreOnlyOwnedDraft() {
        login(7L);
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(task("7", null)));
        BpmTaskOpinionDraft draft = new BpmTaskOpinionDraft();
        draft.setTaskId("task-1");
        draft.setProcessInstanceId("instance-1");
        draft.setNodeKey("approve");
        draft.setRoundNo(1);
        draft.setAssigneeId(7L);
        draft.setComment("保留意见");
        draft.setOpinionData("{\"result\":\"同意\"}");
        when(mapper.selectOne(any())).thenReturn(draft);

        Map<String, Object> restored = controller.get("task-1").getData();
        assertThat(restored).containsEntry("comment", "保留意见");
        assertThat(restored.get("opinionData")).isEqualTo(Map.of("result", "同意"));
    }

    @Test
    @DisplayName("非任务办理人不得读取或覆盖他人暂存")
    void get_shouldRejectOtherUser() {
        login(8L);
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(task("7", null)));
        assertThatThrownBy(() -> controller.get("task-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("当前办理人");
    }

    @Test
    @DisplayName("任务终态后拒绝改写暂存")
    void save_shouldRejectFinishedTask() {
        login(7L);
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(task("7", new Date())));
        assertThatThrownBy(() -> controller.save("task-1",
                new BpmTaskOpinionDraftController.OpinionDraftRequest(null, null, "意见", Map.of())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("任务已结束");
    }

    @Test
    void deleteRejectsAnotherHandlerEvenWhenNoDraftExists() {
        login(8L);
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(task("7", null)));
        assertThatThrownBy(() -> controller.delete("task-1"))
                .isInstanceOf(BaseException.class).hasMessageContaining("当前办理人");
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void actorAndTaskTenantMustMatch() {
        login(7L);
        BpmTaskDTO foreign = task("7", null); foreign.setTenantId("200");
        when(taskFacade.getTask("task-1")).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> controller.get("task-1"))
                .isInstanceOf(BaseException.class).hasMessageContaining("当前租户");
        assertThatThrownBy(() -> controller.save("task-1",
                new BpmTaskOpinionDraftController.OpinionDraftRequest(null, null, "意见", Map.of())))
                .isInstanceOf(BaseException.class).hasMessageContaining("当前租户");
    }

    @Test
    void missingIdentityIsUnauthorized() {
        assertThatThrownBy(() -> controller.get("task-1")).isInstanceOf(BaseException.class)
                .extracting("code").isEqualTo(401);
    }

    private BpmTaskDTO task(String assignee, Date endTime) {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("task-1");
        task.setProcessInstanceId("instance-1");
        task.setTaskDefinitionKey("approve");
        task.setAssignee(assignee);
        task.setEndTime(endTime);
        task.setCreateTime(new Date());
        return task;
    }

    private void login(long userId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(100L);
        LoginUserHolder.set(user);
    }
}
