package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmTaskDeadline;
import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDeadlineSchedulerAdmissionTest {

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), BpmTaskDeadline.class);
    }

    @Test
    void pausedTenantCannotClaimDeadlineOrPublishReminder() {
        BpmTaskDeadlineMapper deadlines = mock(BpmTaskDeadlineMapper.class);
        BpmTaskFacade tasks = mock(BpmTaskFacade.class);
        BpmInstanceService instances = mock(BpmInstanceService.class);
        ObjectProvider<TaskActionService> actionProvider = provider(null);
        ObjectProvider<UserQueryFacade> userProvider = provider(null);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        BpmTaskDeadline deadline = new BpmTaskDeadline();
        deadline.setId(92L);
        deadline.setTenantId(95L);
        deadline.setTaskId("task-95");
        deadline.setRunState("PENDING");
        deadline.setDueAt(LocalDateTime.now().minusMinutes(1));
        deadline.setEscalationFired(0);
        when(deadlines.selectList(any())).thenReturn(List.of(deadline));
        when(tasks.getTask("task-95")).thenReturn(Optional.of(new BpmTaskDTO()));
        TaskDeadlineScheduler scheduler = new TaskDeadlineScheduler(
                deadlines, tasks, instances, actionProvider, userProvider, publisher, new ObjectMapper(),
                (tenantId, claim) -> 0);

        scheduler.scan();

        verify(deadlines, never()).update(any(), any());
        verify(publisher, never()).publish(any());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
