package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskNodeActionPolicyTest {
    private final BpmTaskFacade tasks = mock(BpmTaskFacade.class);
    private final BpmInstanceService instances = mock(BpmInstanceService.class);
    private final BpmProcessDefService definitions = mock(BpmProcessDefService.class);
    private final ApprovalActionService audit = mock(ApprovalActionService.class);
    private final TaskActionService service = new TaskActionService(
            tasks, instances, definitions, null, null, audit, new ObjectMapper(), null);

    @AfterEach void clear() { LoginUserHolder.clear(); }

    private BpmTaskDTO task(Map<String, Object> config) {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId("task-1");
        task.setProcessInstanceId("instance-1");
        task.setTaskDefinitionKey("approval");
        task.setAssignee("1");
        task.setNodeConfig(config);
        return task;
    }

    @Test void missingPolicyPreservesLegacyWhileExplicitEmptyDenies() {
        assertThat(service.resolveAllowedActions(task(Map.of()))).contains("APPROVE", "ADD_SIGN");
        BpmTaskDTO denied = task(Map.of("allowedActions", List.of()));
        assertThat(service.resolveAllowedActions(denied)).isEmpty();
        assertThatThrownBy(() -> service.assertNodeActionAllowed(denied, ApprovalAction.APPROVE))
                .isInstanceOf(BaseException.class);
    }

    @Test void normalApprovalCannotBypassExplicitNodePolicy() {
        LoginUser actor = new LoginUser(); actor.setUserId(1L); actor.setTenantId(0L);
        LoginUserHolder.set(actor);
        BpmTaskDTO task = task(Map.of("allowedActions", List.of("ADD_SIGN")));
        when(tasks.getTask("task-1")).thenReturn(Optional.of(task));
        when(instances.findByProcessInstanceId("instance-1")).thenReturn(Optional.empty());
        ApprovalActionRequest request = new ApprovalActionRequest();
        request.setAction(ApprovalAction.APPROVE);
        assertThatThrownBy(() -> service.execute("task-1", request)).isInstanceOf(BaseException.class);
        verify(tasks, never()).complete(anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test void frozenOpinionDoesNotReadMutableDefinition() {
        Map<String, Object> opinion = Map.of("formId", "frozen", "version", "2");
        assertThat(service.resolveOpinionForm(task(Map.of("opinionForm", opinion)))).isEqualTo(opinion);
        assertThat(service.resolveOpinionForm(task(Map.of()))).isEmpty();
        verifyNoInteractions(definitions);
    }

    @Test void malformedOrUnknownPolicyFailsClosed() {
        for (Object raw : List.of("APPROVE", List.of("UNKNOWN"), List.of(1))) {
            assertThatThrownBy(() -> service.resolveAllowedActions(task(Map.of("allowedActions", raw))))
                    .isInstanceOf(BaseException.class);
        }
        Map<String, Object> explicitNull = new HashMap<>(); explicitNull.put("allowedActions", null);
        assertThatThrownBy(() -> service.resolveAllowedActions(task(explicitNull)))
                .isInstanceOf(BaseException.class);
    }
}
