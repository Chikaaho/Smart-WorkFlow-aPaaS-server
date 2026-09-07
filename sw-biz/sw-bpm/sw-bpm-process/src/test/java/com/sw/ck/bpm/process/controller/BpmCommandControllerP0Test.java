package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.service.BpmCommandService;
import com.sw.ck.bpm.process.service.CommandAcceptService;
import com.sw.ck.bpm.process.service.CommandSyncWaiter;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 同步优先通道控制器行为测试（B1/B3 验收证据）。
 * <ul>
 *   <li>无专用权限 {@code workflow:p0:dispatch} → 403，不得受理</li>
 *   <li>有权限 → P0 车道受理 + 有界等待；COMPLETED/FAILED 返回终态</li>
 *   <li>超时 → 返回受理标识 + ACCEPTED（超时≠失败，可回查），不重复受理</li>
 * </ul>
 */
@DisplayName("P0 同步优先通道测试")
class BpmCommandControllerP0Test {

    private final CommandAcceptService commandAcceptService = mock(CommandAcceptService.class);
    private final BpmCommandService bpmCommandService = mock(BpmCommandService.class);
    private final CommandSyncWaiter syncWaiter = mock(CommandSyncWaiter.class);
    private final PermissionService permissionService = mock(PermissionService.class);

    private final BpmCommandController controller = new BpmCommandController(
            commandAcceptService, bpmCommandService, syncWaiter, permissionService);

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void login(boolean withP0) {
        LoginUser user = new LoginUser();
        user.setUserId(7L);
        user.setTenantId(1L);
        LoginUserHolder.set(user);
        when(permissionService.hasPermi("workflow:p0:dispatch")).thenReturn(withP0);
    }

    private CommandAcceptRespDTO accepted() {
        CommandAcceptRespDTO resp = new CommandAcceptRespDTO();
        resp.setCommandId(99L);
        resp.setCommandKey("TASK_APPROVE:task-1:7");
        resp.setCommandType(CommandTypeEnum.TASK_APPROVE.getCode());
        resp.setChannel(CommandChannelEnum.P0.getCode());
        return resp;
    }

    @Test
    @DisplayName("无专用权限：403 拒绝且不受理")
    void p0WithoutPermission_shouldReject() {
        login(false);
        assertThatThrownBy(() -> controller.acceptTaskAction("task-1", "complete", null, "P0"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("P0");
        org.mockito.Mockito.verifyNoInteractions(commandAcceptService);
        verify(permissionService).hasPermi("workflow:p0:dispatch");
    }

    @Test
    @DisplayName("有权限 + 命令完成：返回 COMPLETED 终态")
    void p0Completed_shouldReturnTerminalStatus() {
        login(true);
        CommandAcceptRespDTO acc = accepted();
        when(commandAcceptService.acceptTaskAction(eq("task-1"), eq(ApprovalAction.APPROVE),
                any(), eq(CommandChannelEnum.P0))).thenReturn(acc);
        BpmCommand done = new BpmCommand();
        done.setId(99L);
        done.setStatus("COMPLETED");
        when(syncWaiter.waitTerminal(99L)).thenReturn(
                new CommandSyncWaiter.WaitResult(CommandSyncWaiter.Outcome.COMPLETED, done));

        R<CommandAcceptRespDTO> result =
                controller.acceptTaskAction("task-1", "complete", null, "P0");

        assertThat(result.getData().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getData().getCommandId()).isEqualTo(99L);
        assertThat(result.getData().getChannel()).isEqualTo("P0");
    }

    @Test
    @DisplayName("有界等待超时：返回受理标识 + ACCEPTED，不伪装完成也不失败")
    void p0Timeout_shouldReturnAcceptedWithCommandId() {
        login(true);
        when(commandAcceptService.acceptTaskAction(eq("task-1"), eq(ApprovalAction.REJECT),
                any(), eq(CommandChannelEnum.P0))).thenReturn(accepted());
        BpmCommand pending = new BpmCommand();
        pending.setId(99L);
        pending.setStatus("PROCESSING");
        when(syncWaiter.waitTerminal(99L)).thenReturn(
                new CommandSyncWaiter.WaitResult(CommandSyncWaiter.Outcome.TIMEOUT, pending));

        R<CommandAcceptRespDTO> result =
                controller.acceptTaskAction("task-1", "reject", null, "P0");

        assertThat(result.getData().getStatus()).isEqualTo("ACCEPTED");
        assertThat(result.getData().getCommandId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("命令终态失败：P0 同步返回 FAILED（可回查失败原因）")
    void p0Failed_shouldReturnFailedStatus() {
        login(true);
        when(commandAcceptService.acceptTaskAction(eq("task-1"), eq(ApprovalAction.APPROVE),
                any(), eq(CommandChannelEnum.P0))).thenReturn(accepted());
        BpmCommand failed = new BpmCommand();
        failed.setId(99L);
        failed.setStatus("FAILED");
        failed.setFailureReason("任务已被处理");
        when(syncWaiter.waitTerminal(99L)).thenReturn(
                new CommandSyncWaiter.WaitResult(CommandSyncWaiter.Outcome.FAILED, failed));

        R<CommandAcceptRespDTO> result =
                controller.acceptTaskAction("task-1", "complete", null, "P0");

        assertThat(result.getData().getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("未知通道：明确拒绝")
    void unknownChannel_shouldReject() {
        login(false);
        assertThatThrownBy(() -> controller.acceptTaskAction("task-1", "complete", null, "URGENT"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("未知通道");
    }
}
