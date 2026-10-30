package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.mapper.BpmTaskOpinionDraftMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BpmOpinionDraftAuthorizationTest {
    @Configuration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class SecurityConfig { }

    @AfterEach void clear() {
        LoginUserHolder.clear(); SecurityContextHolder.clearContext();
    }

    @Test void authenticatedOwnerWithoutMenuOrButtonPermissionCanManageOnlyOwnDraft() {
        BpmTaskFacade tasks = mock(BpmTaskFacade.class);
        BpmTaskOpinionDraftMapper drafts = mock(BpmTaskOpinionDraftMapper.class);
        BpmTaskDTO task = new BpmTaskDTO(); task.setTaskId("task-1");
        task.setAssignee("7"); task.setTenantId("100"); task.setProcessInstanceId("pi-1");
        when(tasks.getTask("task-1")).thenReturn(Optional.of(task));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SecurityConfig.class);
            context.registerBean(BpmTaskOpinionDraftController.class,
                    () -> new BpmTaskOpinionDraftController(tasks, drafts, new ObjectMapper()));
            context.refresh();
            BpmTaskOpinionDraftController controller = context.getBean(BpmTaskOpinionDraftController.class);
            LoginUser actor = new LoginUser(); actor.setUserId(7L); actor.setTenantId(100L);
            LoginUserHolder.set(actor);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("actor", null, List.of()));
            assertThat(controller.get("task-1").getCode()).isZero();
            assertThat(controller.save("task-1", new BpmTaskOpinionDraftController.OpinionDraftRequest(
                    null, null, "尚未填完的意见", Map.of())).getCode()).isZero();
            assertThat(controller.delete("task-1").getCode()).isZero();
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> controller.get("task-1"))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verify(tasks, times(3)).getTask("task-1");
        }
    }
}
