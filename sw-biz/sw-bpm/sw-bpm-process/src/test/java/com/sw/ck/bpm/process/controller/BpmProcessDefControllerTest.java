package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // ==================== GET /workflow/defs ====================

    @Nested
    @DisplayName("GET /workflow/defs")
    class ListDefsTests {

        @Test
        @DisplayName("列表按 createBy 回填创建人展示名")
        void listDefs_shouldFillCreatorNames() {
            BpmProcessDef def = new BpmProcessDef();
            def.setId(1L);
            def.setCreateBy(7L);
            PageResult<BpmProcessDef> page = new PageResult<>();
            page.setRecords(List.of(def));
            page.setTotal(1);
            when(bpmProcessDefService.listDefs(any(PageParam.class), eq("form-1"), isNull())).thenReturn(page);
            when(userQueryFacade.getUserDisplayNames(anyCollection()))
                    .thenReturn(Optional.of(Map.of(7L, "系统管理员")));

            R<PageResult<BpmProcessDef>> result = controller.listDefs(new PageParam(), "form-1", null);

            assertThat(result.getData().getRecords().get(0).getCreatorName()).isEqualTo("系统管理员");
        }

        @Test
        @DisplayName("查询上下文缺失时创建人保持空值，不影响列表返回")
        void listDefs_shouldTolerateMissingNameContext() {
            BpmProcessDef def = new BpmProcessDef();
            def.setId(2L);
            def.setCreateBy(8L);
            PageResult<BpmProcessDef> page = new PageResult<>();
            page.setRecords(List.of(def));
            page.setTotal(1);
            when(bpmProcessDefService.listDefs(any(PageParam.class), isNull(), isNull())).thenReturn(page);
            when(userQueryFacade.getUserDisplayNames(anyCollection())).thenReturn(Optional.empty());

            R<PageResult<BpmProcessDef>> result = controller.listDefs(new PageParam(), null, null);

            assertThat(result.getData().getRecords()).hasSize(1);
            assertThat(result.getData().getRecords().get(0).getCreatorName()).isNull();
        }
    }

    // ==================== Q69（459:14712）超管租户过滤门控 + 租户名回填 ====================

    @Nested
    @DisplayName("GET /workflow/defs 租户过滤（owner-20261030-u020-q69-def-tenant）")
    class DefTenantFilterTests {

        private PageResult<BpmProcessDef> def(long tenantId) {
            BpmProcessDef record = new BpmProcessDef();
            record.setId(9L);
            record.setTenantId(tenantId);
            PageResult<BpmProcessDef> page = new PageResult<>();
            page.setRecords(List.of(record));
            page.setTotal(1);
            return page;
        }

        @AfterEach
        void clearHolder() {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }

        @Test
        @DisplayName("非超管传 tenantId → 参数被忽略（服务收到 null），隔离由既有拦截器保证")
        void listDefs_nonSuperAdmin_tenantIdIgnored() {
            com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
            user.setSuperAdmin(false);
            com.sw.ck.security.holder.LoginUserHolder.set(user);
            when(bpmProcessDefService.listDefs(any(PageParam.class), isNull(), isNull()))
                    .thenReturn(def(100L));

            R<PageResult<BpmProcessDef>> result = controller.listDefs(new PageParam(), null, 0L);

            verify(bpmProcessDefService).listDefs(any(PageParam.class), isNull(), isNull());
            assertThat(result.getData().getRecords()).hasSize(1);
        }

        @Test
        @DisplayName("超管传 tenantId → 原样下传服务；租户名按映射回填")
        void listDefs_superAdmin_tenantIdPassedAndTenantNameFilled() {
            com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
            user.setSuperAdmin(true);
            com.sw.ck.security.holder.LoginUserHolder.set(user);
            when(bpmProcessDefService.listDefs(any(PageParam.class), isNull(), eq(100L)))
                    .thenReturn(def(100L));
            org.springframework.jdbc.core.JdbcTemplate jdbc =
                    mock(org.springframework.jdbc.core.JdbcTemplate.class);
            when(jdbc.queryForList(anyString()))
                    .thenReturn(List.of(Map.of("id", 100L, "name", "租户100")));
            BpmProcessDefController superController = new BpmProcessDefController(
                    bpmProcessDefService, objectMapper, userQueryFacade, null,
                    mock(com.sw.ck.bpm.process.service.ProcessThemeService.class),
                    null, null, jdbc);

            R<PageResult<BpmProcessDef>> result = superController.listDefs(new PageParam(), null, 100L);

            verify(bpmProcessDefService).listDefs(any(PageParam.class), isNull(), eq(100L));
            assertThat(result.getData().getRecords().get(0).getTenantName()).isEqualTo("租户100");
        }
    }

    // ==================== GET /workflow/defs 展示字段回填 ====================

    @Nested
    @DisplayName("GET /workflow/defs 展示字段回填（分类/更新人/实例数）")
    class ListDisplayFieldsTests {

        private final com.sw.ck.bpm.process.mapper.BpmCategoryMapper categoryMapper =
                mock(com.sw.ck.bpm.process.mapper.BpmCategoryMapper.class);
        private final com.sw.ck.bpm.process.mapper.BpmInstanceMapper instanceMapper =
                mock(com.sw.ck.bpm.process.mapper.BpmInstanceMapper.class);

        private final BpmProcessDefController enrichedController = new BpmProcessDefController(
                bpmProcessDefService, objectMapper, userQueryFacade, null,
                mock(com.sw.ck.bpm.process.service.ProcessThemeService.class),
                categoryMapper, instanceMapper, null);

        @Test
        @DisplayName("分类名/更新人/实例数按批量结果回填（数据侧已有字段，不逐行查库）")
        void listDefs_shouldFillCategoryUpdaterAndInstanceCount() {
            BpmProcessDef def = new BpmProcessDef();
            def.setId(3L);
            def.setProcessKey("pk-1");
            def.setCategoryId(11L);
            def.setCreateBy(7L);
            def.setUpdateBy(9L);
            PageResult<BpmProcessDef> page = new PageResult<>();
            page.setRecords(List.of(def));
            page.setTotal(1);
            when(bpmProcessDefService.listDefs(any(PageParam.class), isNull(), isNull())).thenReturn(page);
            when(userQueryFacade.getUserDisplayNames(anyCollection()))
                    .thenReturn(Optional.of(Map.of(9L, "流程管理员")));
            com.sw.ck.bpm.process.entity.BpmCategory category =
                    new com.sw.ck.bpm.process.entity.BpmCategory();
            category.setId(11L);
            category.setName("运维");
            when(categoryMapper.selectBatchIds(anyCollection())).thenReturn(List.of(category));
            when(instanceMapper.selectMaps(any()))
                    .thenReturn(List.of(Map.of("processKey", "pk-1", "cnt", 5L)));

            R<PageResult<BpmProcessDef>> result = enrichedController.listDefs(new PageParam(), null, null);

            BpmProcessDef record = result.getData().getRecords().get(0);
            assertThat(record.getCategoryName()).isEqualTo("运维");
            assertThat(record.getUpdatedBy()).isEqualTo("流程管理员");
            assertThat(record.getInstanceCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("无实例的流程实例数为 0，未分类保持空值")
        void listDefs_shouldDefaultZeroInstanceCountAndNullCategory() {
            BpmProcessDef def = new BpmProcessDef();
            def.setId(4L);
            def.setProcessKey("pk-2");
            def.setCreateBy(7L);
            PageResult<BpmProcessDef> page = new PageResult<>();
            page.setRecords(List.of(def));
            page.setTotal(1);
            when(bpmProcessDefService.listDefs(any(PageParam.class), isNull(), isNull())).thenReturn(page);
            when(userQueryFacade.getUserDisplayNames(anyCollection()))
                    .thenReturn(Optional.of(Map.of(7L, "系统管理员")));
            when(instanceMapper.selectMaps(any())).thenReturn(List.of());

            R<PageResult<BpmProcessDef>> result = enrichedController.listDefs(new PageParam(), null, null);

            BpmProcessDef record = result.getData().getRecords().get(0);
            assertThat(record.getCategoryName()).isNull();
            assertThat(record.getUpdatedBy()).isEqualTo("系统管理员");
            assertThat(record.getInstanceCount()).isZero();
        }
    }

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
