package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysWorkspaceCardType;
import com.sw.ck.system.service.UserWorkspaceService;
import com.sw.ck.system.service.WorkspaceCardTypeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkspaceCardTypeControllerTest {

    private final UserWorkspaceService workspaceService = mock(UserWorkspaceService.class);
    private final WorkspaceCardTypeService cardTypeService = mock(WorkspaceCardTypeService.class);
    private final UserWorkspaceController controller =
            new UserWorkspaceController(workspaceService, cardTypeService);

    @Test
    void layout_shouldExposeUserLayout() {
        when(workspaceService.getLayout()).thenReturn(Map.of("custom", true));

        R<Map<String, Object>> result = controller.layout();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("custom", true);
    }

    @Test
    void cardTypes_shouldReturnCurrentTenantTypes() {
        SysWorkspaceCardType type = new SysWorkspaceCardType();
        type.setTypeCode("todo");
        type.setRendererKey("todo");
        when(cardTypeService.listAvailable()).thenReturn(List.of(type));

        R<List<SysWorkspaceCardType>> result = controller.cardTypes();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).extracting(SysWorkspaceCardType::getTypeCode).containsExactly("todo");
    }

    @Test
    void updateCardType_shouldBindPathIdBeforeDelegating() {
        SysWorkspaceCardType type = new SysWorkspaceCardType();
        type.setTypeCode("todo");

        R<Void> result = controller.updateCardType(42L, type);

        assertThat(result.getCode()).isZero();
        assertThat(type.getId()).isEqualTo(42L);
        verify(cardTypeService).update(type);
    }
}
