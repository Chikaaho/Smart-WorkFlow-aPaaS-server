package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * {@link BpmProcessDefController} 单元测试。
 * <p>
 * 纯 Mockito，不装载 Spring 上下文。使用 hand-rolled mock 模式（与 {@code BpmTodoControllerTest} 一致）。
 * </p>
 */
@DisplayName("BpmProcessDefController 单元测试")
class BpmProcessDefControllerTest {

    private final BpmProcessDefService bpmProcessDefService = mock(BpmProcessDefService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final com.sw.ck.system.api.user.UserQueryFacade userQueryFacade =
            mock(com.sw.ck.system.api.user.UserQueryFacade.class);

    private final BpmProcessDefController controller = new BpmProcessDefController(
            bpmProcessDefService, objectMapper, userQueryFacade, null,
            mock(com.sw.ck.bpm.process.service.ProcessThemeService.class));

    // ==================== GET /workflow/defs/{id}/bpmn-xml ====================

    @Nested
    @DisplayName("GET /workflow/defs/{id}/bpmn-xml")
    class BpmnXmlTests {

        @Test
        @DisplayName("正常返回 → R code=0, data=XML 字符串")
        void getBpmnXml_shouldReturnXmlString() {
            String expectedXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions/>";
            when(bpmProcessDefService.getBpmnXml(1L)).thenReturn(expectedXml);

            R<String> result = controller.getBpmnXml(1L);

            assertThat(result.getCode()).isZero();
            assertThat(result.getData()).isEqualTo(expectedXml);
            verify(bpmProcessDefService).getBpmnXml(1L);
        }

        @Test
        @DisplayName("流程未发布 → 向上传播 BaseException（不在 Controller 层转换）")
        void getBpmnXml_notPublished_shouldPropagateException() {
            when(bpmProcessDefService.getBpmnXml(2L))
                    .thenThrow(new BaseException(BpmErrorCode.PROCESS_NOT_PUBLISHED));

            assertThatThrownBy(() -> controller.getBpmnXml(2L))
                    .isInstanceOf(BaseException.class)
                    .satisfies(e -> {
                        BaseException be = (BaseException) e;
                        assertThat(be.getCode()).isEqualTo(BpmErrorCode.PROCESS_NOT_PUBLISHED.getCode());
                    });
        }

        @Test
        @DisplayName("ID 不存在 → 向上传播 BaseException 含 PROCESS_DEF_NOT_FOUND")
        void getBpmnXml_idNotFound_shouldPropagateException() {
            when(bpmProcessDefService.getBpmnXml(999L))
                    .thenThrow(new BaseException(BpmErrorCode.PROCESS_DEF_NOT_FOUND));

            assertThatThrownBy(() -> controller.getBpmnXml(999L))
                    .isInstanceOf(BaseException.class)
                    .satisfies(e -> {
                        BaseException be = (BaseException) e;
                        assertThat(be.getCode()).isEqualTo(BpmErrorCode.PROCESS_DEF_NOT_FOUND.getCode());
                    });
        }
    }
}
