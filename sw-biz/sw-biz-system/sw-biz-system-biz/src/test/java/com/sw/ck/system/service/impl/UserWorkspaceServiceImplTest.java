package com.sw.ck.system.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysUserWorkspace;
import com.sw.ck.system.entity.SysWorkspaceCardType;
import com.sw.ck.system.mapper.SysUserWorkspaceMapper;
import com.sw.ck.system.service.WorkspaceCardTypeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工作台布局兼容扩展：内置数据组件、多实例、12 列与高度。 */
@DisplayName("工作台布局服务·内置组件兼容")
class UserWorkspaceServiceImplTest {

    private SysUserWorkspaceMapper workspaceMapper;
    private WorkspaceCardTypeService cardTypeService;
    private UserWorkspaceServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        workspaceMapper = mock(SysUserWorkspaceMapper.class);
        cardTypeService = mock(WorkspaceCardTypeService.class);
        SysWorkspaceCardType todo = new SysWorkspaceCardType();
        todo.setTypeCode("todo");
        todo.setRendererKey("todo");
        todo.setDefaultOrder(1);
        todo.setDefaultSpan(1);
        todo.setStatus(0);
        when(cardTypeService.listAvailable()).thenReturn(List.of(todo));
        service = new UserWorkspaceServiceImpl(workspaceMapper, objectMapper, cardTypeService);

        LoginUser user = new LoginUser();
        user.setUserId(77L);
        user.setTenantId(100L);
        user.setUsername("workspace_user");
        LoginUserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private Map<String, Object> card(String typeCode, String instanceId, int span, int height) {
        return Map.of(
                "typeCode", typeCode,
                "instanceId", instanceId,
                "visible", true,
                "order", 1,
                "span", span,
                "height", height,
                "metadata", Map.of("title", instanceId)
        );
    }

    @Test
    @DisplayName("同类型多实例 → 以 instanceId 去重并保留 12 列/高度")
    void saveLayout_shouldAllowMultipleBuiltInInstances() throws Exception {
        Map<String, Object> layout = Map.of(
                "cards", List.of(
                        card("chart_bar", "metric_a", 3, 140),
                        card("chart_bar", "metric_b", 6, 320),
                        card("metric", "metric_c", 12, 160)
                ),
                "favoriteItemKeys", List.of()
        );

        service.saveLayout(layout);

        ArgumentCaptor<SysUserWorkspace> captor = ArgumentCaptor.forClass(SysUserWorkspace.class);
        verify(workspaceMapper).insert(captor.capture());
        JsonNode saved = objectMapper.readTree(captor.getValue().getLayoutJson());
        assertThat(saved.get("cards")).hasSize(3);
        assertThat(saved.get("cards").get(0).get("instanceId").asText()).isEqualTo("metric_a");
        assertThat(saved.get("cards").get(0).get("span").asInt()).isEqualTo(3);
        assertThat(saved.get("cards").get(1).get("height").asInt()).isEqualTo(320);
    }

    @Test
    @DisplayName("旧布局无实例标识 → 仍拒绝重复 typeCode")
    void saveLayout_shouldRejectLegacyDuplicateTypeCode() {
        Map<String, Object> layout = Map.of(
                "cards", List.of(
                        Map.of("typeCode", "todo", "order", 1, "span", 1),
                        Map.of("typeCode", "todo", "order", 2, "span", 2)
                )
        );
        assertThatThrownBy(() -> service.saveLayout(layout))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("卡片类型重复");
    }

    @Test
    @DisplayName("内置组件宽度超过 12 列或高度越界 → 拒绝")
    void saveLayout_shouldRejectInvalidGridGeometry() {
        Map<String, Object> wide = Map.of("cards",
                List.of(card("metric", "m1", 13, 160)));
        assertThatThrownBy(() -> service.saveLayout(wide))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("1-12");

        Map<String, Object> shortCard = Map.of("cards",
                List.of(card("metric", "m2", 3, 80)));
        assertThatThrownBy(() -> service.saveLayout(shortCard))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("100-1600");
    }

    @Test
    @DisplayName("读取旧布局 → 保留内置组件实例/高度，不回落默认")
    void getLayout_shouldNormalizeBuiltInCards() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "cards", List.of(
                        card("metric", "m1", 3, 140),
                        card("chart_line", "c1", 4, 300)
                ),
                "favoriteItemKeys", List.of()
        ));
        SysUserWorkspace row = new SysUserWorkspace();
        row.setUserId(77L);
        row.setLayoutJson(json);
        when(workspaceMapper.selectOne(any())).thenReturn(row);

        Map<String, Object> result = service.getLayout();
        assertThat(result.get("custom")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> layout = (Map<String, Object>) result.get("layout");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) layout.get("cards");
        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(c -> c.get("typeCode"))
                .containsExactly("metric", "chart_line");
        assertThat(cards.get(0)).containsEntry("instanceId", "m1");
        assertThat(cards.get(1)).containsEntry("height", 300);
    }
}
