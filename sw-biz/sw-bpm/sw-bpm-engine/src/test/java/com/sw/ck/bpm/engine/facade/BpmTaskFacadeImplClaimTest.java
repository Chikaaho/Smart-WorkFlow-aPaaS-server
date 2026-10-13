package com.sw.ck.bpm.engine.facade;

import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.common.exception.BaseException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅱ（A07/A11）候选领取语义测试：候选组任务唯一合法办理入口。
 * <p>
 * 覆盖：候选领取成功（claim + 其余候选链接移除=竞争单结果）、已领取后领取方确定性
 * 失败、非候选领取拒绝、任务缺失/已处理冲突。
 * </p>
 */
@DisplayName("候选任务领取语义测试")
class BpmTaskFacadeImplClaimTest {

    private final TaskService taskService = mock(TaskService.class);
    private final TaskQuery taskQuery = mock(TaskQuery.class);
    private final BpmTaskFacadeImpl facade = new BpmTaskFacadeImpl(taskService,
            mock(RuntimeService.class), mock(RepositoryService.class), mock(HistoryService.class));

    @BeforeEach
    void setUp() {
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(anyString())).thenReturn(taskQuery);
        when(taskQuery.taskCandidateOrAssigned(anyString())).thenReturn(taskQuery);
    }

    private Task unassignedTask() {
        Task task = mock(Task.class);
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        when(task.getAssignee()).thenReturn(null);
        return task;
    }

    private IdentityLink candidateUser(String userId) {
        IdentityLink link = mock(IdentityLink.class);
        when(link.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(link.getUserId()).thenReturn(userId);
        return link;
    }

    private IdentityLink candidateGroup(String groupId) {
        IdentityLink link = mock(IdentityLink.class);
        when(link.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(link.getGroupId()).thenReturn(groupId);
        return link;
    }

    @Test
    @DisplayName("候选领取成功：claim 本人一次，其余候选用户/组链接移除（竞争单结果），本人链接保留")
    void candidateClaimsTaskAndRemainingCandidateLinksRemoved() {
        Task task = unassignedTask();
        // lockFor 快照 + 存在性检查 + 候选判定：三次 singleResult 均命中本任务
        when(taskQuery.singleResult()).thenReturn(task);
        // link mock 先在 when() 外构建，避免嵌套 stubbing
        java.util.List<IdentityLink> links = java.util.List.of(
                candidateUser("2"), candidateUser("3"), candidateGroup("g-ops"));
        when(taskService.getIdentityLinksForTask("t1")).thenReturn(links);

        var outcome = facade.claimTask("t1", "2");

        assertThat(outcome).isPresent();
        assertThat(outcome.orElseThrow()).isEqualTo(MutationOutcome.APPLIED);
        verify(taskService).claim("t1", "2");
        verify(taskService).deleteUserIdentityLink("t1", "3", IdentityLinkType.CANDIDATE);
        verify(taskService).deleteGroupIdentityLink("t1", "g-ops", IdentityLinkType.CANDIDATE);
        verify(taskService, never()).deleteUserIdentityLink(eq("t1"), eq("2"),
                eq(IdentityLinkType.CANDIDATE));
    }

    @Test
    @DisplayName("已被领取：后到候选确定性失败（任务已被领取），不产生任何领取动作")
    void claimAfterAssigneeSetFailsDeterministically() {
        Task claimed = unassignedTask();
        when(claimed.getAssignee()).thenReturn("3");
        when(taskQuery.singleResult()).thenReturn(claimed);

        assertThatThrownBy(() -> facade.claimTask("t1", "2"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("任务已被领取");

        verify(taskService, never()).claim(anyString(), anyString());
    }

    @Test
    @DisplayName("非候选领取拒绝：无权领取该任务，不产生任何领取动作")
    void nonCandidateClaimForbidden() {
        Task task = unassignedTask();
        // 存在性检查命中（lockFor+存在性）；候选判定零匹配（当前用户不在候选集合）
        when(taskQuery.singleResult()).thenReturn(task, task, (Task) null);

        assertThatThrownBy(() -> facade.claimTask("t1", "99"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("无权领取该任务");

        verify(taskService, never()).claim(anyString(), anyString());
    }

    @Test
    @DisplayName("任务不存在或已被处理：领取抛 2305 冲突，不产生任何领取动作")
    void claimMissingTaskConflict() {
        when(taskQuery.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> facade.claimTask("t1", "2"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("任务不存在或已被处理");

        verify(taskService, never()).claim(anyString(), anyString());
    }
}
