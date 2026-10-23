package com.sw.ck.bpm.process.controller;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.CopyRecordMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.storage.api.StorageFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowAttachmentControllerTest {
    private final StorageFacade storage = mock(StorageFacade.class);
    private final FormDefinitionService forms = mock(FormDefinitionService.class);
    private final BpmInstanceService instances = mock(BpmInstanceService.class);
    private final BpmTaskFacade tasks = mock(BpmTaskFacade.class);
    private final CopyRecordMapper copies = mock(CopyRecordMapper.class);
    private final WorkflowAttachmentController controller = new WorkflowAttachmentController(
            storage, forms, instances, tasks, copies);
    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<BpmInstance> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
    private final BpmInstance instance = new BpmInstance();

    @BeforeEach
    void setup() {
        LoginUser actor = new LoginUser();
        actor.setUserId(2L);
        actor.setTenantId(9L);
        LoginUserHolder.set(actor);
        instance.setInitiatorId(2L);
        instance.setProcessInstanceId("pi-1");
        instance.setFormKey("form");
        instance.setBusinessKey("record");
        when(instances.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        when(query.one()).thenReturn(instance);
        when(forms.canCurrentUserAccessAttachment(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(false));
    }

    @AfterEach
    void cleanup() {
        LoginUserHolder.clear();
    }

    private void allowFile() {
        when(forms.canCurrentUserAccessAttachment("form", "record", "file"))
                .thenReturn(Optional.of(true));
        when(storage.download("file"))
                .thenReturn(Optional.of(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    private void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,
                e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(storage);
    }

    @Test
    void anonymousRequestCannotReadStorage() {
        LoginUserHolder.clear();
        assertThatThrownBy(() -> controller.download("record", "file", "plan.pdf", null))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode()).isEqualTo(401));
        verifyNoInteractions(instances, forms, storage);
    }

    @Test
    void recordOwnershipDoesNotAuthorizeAnUnrelatedStorageKey() {
        forbidden(() -> controller.download("record", "unrelated", "plan.pdf", null));
        verify(forms).canCurrentUserAccessAttachment("form", "record", "unrelated");
    }

    @Test
    void associatedVisibleFileUsesInstanceFormAndOriginalDownloadResponse() throws Exception {
        allowFile();
        var response = controller.download("record", "file", "plan.pdf", null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("plan.pdf");
        assertThat(response.getBody().getInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void forgedFormKeyCannotSwitchRecordContext() {
        forbidden(() -> controller.download("record", "file", "plan.pdf", "other-form"));
        verifyNoInteractions(forms);
    }

    @Test
    void managerNeedsExplicitFormForARecordWithoutAnInstance() throws Exception {
        LoginUserHolder.get().setPermissions(List.of("form:data:list"));
        when(query.one()).thenReturn(null);
        forbidden(() -> controller.download("record", "file", "plan.pdf", null));
        allowFile();
        assertThat(controller.download("record", "file", "plan.pdf", "form")
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void missingAccessDecisionFailsClosed() {
        when(forms.canCurrentUserAccessAttachment("form", "record", "file"))
                .thenReturn(Optional.empty());
        forbidden(() -> controller.download("record", "file", "plan.pdf", null));
    }

    @Test
    void unrelatedActorCannotReachFieldOrStorageReads() {
        instance.setInitiatorId(3L);
        when(tasks.queryByProcessInstance("pi-1")).thenReturn(Optional.empty());
        forbidden(() -> controller.download("record", "file", "plan.pdf", null));
        verifyNoInteractions(forms);
    }

    @Test
    void currentCandidateKeepsAccessToAnAssociatedVisibleFile() throws Exception {
        instance.setInitiatorId(3L);
        BpmTaskDTO task = new BpmTaskDTO();
        task.setCandidateUserIds(List.of("2"));
        when(tasks.queryByProcessInstance("pi-1")).thenReturn(Optional.of(List.of(task)));
        allowFile();
        assertThat(controller.download("record", "file", "plan.pdf", null)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void missingFileStillReturnsNotFoundAfterAssociationCheck() {
        when(forms.canCurrentUserAccessAttachment("form", "record", "file"))
                .thenReturn(Optional.of(true));
        when(storage.download("file")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.download("record", "file", "plan.pdf", null))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getCode()).isEqualTo(404));
    }
}
