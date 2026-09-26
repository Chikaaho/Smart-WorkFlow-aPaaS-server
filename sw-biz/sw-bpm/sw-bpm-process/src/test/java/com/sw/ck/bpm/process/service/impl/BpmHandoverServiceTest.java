package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmAuthorizeRule;
import com.sw.ck.bpm.process.entity.BpmHandover;
import com.sw.ck.bpm.process.entity.BpmHandoverItem;
import com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper;
import com.sw.ck.bpm.process.mapper.BpmHandoverItemMapper;
import com.sw.ck.bpm.process.mapper.BpmHandoverMapper;
import com.sw.ck.bpm.process.service.BpmHandoverService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I4 §3.6 交接行为：范围迁移、重试零重复、越权/同用户拒绝、代理规则显式随迁、
 * 历史零改写（只新增明细行）。
 */
class BpmHandoverServiceTest {

    private final BpmHandoverMapper handoverMapper = mock(BpmHandoverMapper.class);
    private final BpmHandoverItemMapper itemMapper = mock(BpmHandoverItemMapper.class);
    private final BpmAuthorizeRuleMapper ruleMapper = mock(BpmAuthorizeRuleMapper.class);
    private final BpmTaskFacade taskFacade = mock(BpmTaskFacade.class);
    private final UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
    private final BpmHandoverService service = new BpmHandoverServiceImpl(
            handoverMapper, itemMapper, ruleMapper, taskFacade, userQueryFacade);

    @BeforeEach
    void login() {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(0L);
        user.setSuperAdmin(false);
        LoginUserHolder.set(user);
    }

    @AfterEach
    void clear() {
        LoginUserHolder.clear();
    }

    private static BpmTaskDTO task(String taskId, String defKey, String assignee) {
        BpmTaskDTO dto = new BpmTaskDTO();
        dto.setTaskId(taskId);
        dto.setProcessInstanceId("pi-" + taskId);
        dto.setProcessDefinitionKey(defKey);
        dto.setAssignee(assignee);
        return dto;
    }

    @Test
    void shouldMigrateScopedTasksAndRecordPerItemList() {
        when(userQueryFacade.findActiveUserIds(anyCollection(), eq(0L))).thenReturn(java.util.Optional.of(List.of(20L)));
        when(taskFacade.queryTodo(anyString(), eq("10"))).thenReturn(java.util.Optional.of(List.of(
                task("t1", "leave_def", "10"),
                task("t2", "expense_def", "10"),
                task("t3", "leave_def", "30")))); // 办理人已变化 → FAILED 不改写
        when(taskFacade.setAssignee(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(com.sw.ck.bpm.api.result.MutationOutcome.APPLIED));

        BpmHandover handover = service.handover(10L, 20L, List.of("leave_def"), false);

        assertThat(handover.getStatus()).isEqualTo("PARTIAL");
        assertThat(handover.getTotalItems()).isEqualTo(2); // expense_def 不在范围
        assertThat(handover.getMigratedItems()).isEqualTo(1);
        assertThat(handover.getFailedItems()).isEqualTo(1);
        verify(taskFacade).setAssignee("t1", "20");
        verify(taskFacade, never()).setAssignee(eq("t2"), anyString());
        List<BpmHandoverItem> rows = itemsCaptor();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(r -> "t1".equals(r.getTaskId())).findFirst().orElseThrow()
                .getResult()).isEqualTo("MIGRATED");
        assertThat(rows.stream().filter(r -> "t3".equals(r.getTaskId())).findFirst().orElseThrow()
                .getFailReason()).contains("30");
    }

    @Test
    void shouldSkipAlreadyMigratedTaskOnRetry() {
        when(userQueryFacade.findActiveUserIds(anyCollection(), eq(0L))).thenReturn(java.util.Optional.of(List.of(20L)));
        when(taskFacade.queryTodo(anyString(), eq("10"))).thenReturn(java.util.Optional.of(List.of(
                task("t1", "leave_def", "20")))); // 上轮已迁给目标用户
        BpmHandover handover = service.handover(10L, 20L, List.of(), false);
        assertThat(handover.getMigratedItems()).isZero();
        verify(taskFacade, never()).setAssignee(anyString(), anyString());
        assertThat(itemsCaptor().get(0).getResult()).isEqualTo("SKIPPED_ALREADY_MIGRATED");
    }

    @Test
    void shouldRejectSameUserAndInactiveTarget() {
        assertThatThrownBy(() -> service.handover(10L, 10L, List.of(), false))
                .isInstanceOf(BaseException.class);
        when(userQueryFacade.findActiveUserIds(anyCollection(), eq(0L))).thenReturn(java.util.Optional.of(List.of()));
        assertThatThrownBy(() -> service.handover(10L, 99L, List.of(), false))
                .isInstanceOf(BaseException.class).hasMessageContaining("目标用户无效");
    }

    @Test
    void shouldMigrateProxyRulesOnlyWhenExplicit() {
        when(userQueryFacade.findActiveUserIds(anyCollection(), eq(0L))).thenReturn(java.util.Optional.of(List.of(20L)));
        when(taskFacade.queryTodo(anyString(), eq("10"))).thenReturn(java.util.Optional.of(List.of()));
        BpmAuthorizeRule rule = new BpmAuthorizeRule();
        rule.setId(7L);
        rule.setPrincipalId(10L);
        rule.setAgentId(30L);
        rule.setStatus("ACTIVE");
        rule.setStartAt(LocalDateTime.now().minusDays(1));
        rule.setEndAt(LocalDateTime.now().plusDays(7));
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        // 不勾选：规则不动
        service.handover(10L, 20L, List.of(), false);
        verify(ruleMapper, never()).insert(any(BpmAuthorizeRule.class));

        // 显式勾选：复制规则给目标 + 原规则标记迁移（不改写历史行内容语义）
        BpmHandover handover = service.handover(10L, 20L, List.of(), true);
        verify(ruleMapper).insert(any(BpmAuthorizeRule.class));
        assertThat(handover.getMigratedItems()).isEqualTo(1);
    }

    private List<BpmHandoverItem> itemsCaptor() {
        org.mockito.ArgumentCaptor<BpmHandoverItem> captor =
                org.mockito.ArgumentCaptor.forClass(BpmHandoverItem.class);
        verify(itemMapper, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues();
    }
}
