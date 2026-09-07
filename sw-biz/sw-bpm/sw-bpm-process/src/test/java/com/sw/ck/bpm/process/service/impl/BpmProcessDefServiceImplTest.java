package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmDeployFacade;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.validator.GraphValidator;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link BpmProcessDefServiceImpl} 单元测试。
 * <p>
 * 纯 Mockito + {@link MockitoExtension}，不装载 Spring 上下文。
 * 验证 {@link BpmProcessDefServiceImpl#getBpmnXml(Long)} 正常/异常路径。
 * </p>
 */
@DisplayName("BpmProcessDefServiceImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class BpmProcessDefServiceImplTest {

    @Mock
    private BpmProcessDefMapper mapper;
    @Mock
    private GraphValidator graphValidator;
    @Mock
    private FormDefinitionService formDefinitionService;
    @Mock
    private BpmDeployFacade bpmDeployFacade;

    @InjectMocks
    private BpmProcessDefServiceImpl service;

    // ==================== getBpmnXml ====================

    @Nested
    @DisplayName("getBpmnXml 方法")
    class GetBpmnXmlTests {

        private BpmProcessDef createPublishedDef() {
            BpmProcessDef def = new BpmProcessDef();
            def.setId(1L);
            def.setStatus("PUBLISHED");
            def.setProcessDefinitionId("proc-def-1");
            return def;
        }

        private BpmProcessDef createDraftDef() {
            BpmProcessDef def = new BpmProcessDef();
            def.setId(2L);
            def.setStatus("DRAFT");
            def.setProcessDefinitionId(null);
            return def;
        }

        @Test
        @DisplayName("正常：流程已发布 → 委托 Facade 并返回 XML")
        void getBpmnXml_shouldDelegateToFacade() {
            BpmProcessDef def = createPublishedDef();
            when(mapper.selectById(1L)).thenReturn(def);
            when(bpmDeployFacade.getBpmnXml("proc-def-1")).thenReturn("<xml/>");

            String result = service.getBpmnXml(1L);

            assertThat(result).isEqualTo("<xml/>");
            verify(bpmDeployFacade).getBpmnXml("proc-def-1");
        }

        @Test
        @DisplayName("异常：流程为 DRAFT 状态 → 抛 BaseException 含 PROCESS_NOT_PUBLISHED")
        void getBpmnXml_draftStatus_shouldThrowNotPublished() {
            BpmProcessDef def = createDraftDef();
            when(mapper.selectById(2L)).thenReturn(def);

            assertThatThrownBy(() -> service.getBpmnXml(2L))
                    .isInstanceOf(BaseException.class)
                    .satisfies(e -> {
                        BaseException be = (BaseException) e;
                        assertThat(be.getCode()).isEqualTo(BpmErrorCode.PROCESS_NOT_PUBLISHED.getCode());
                        assertThat(be.getMessage()).contains(BpmErrorCode.PROCESS_NOT_PUBLISHED.getMessage());
                    });

            verifyNoInteractions(bpmDeployFacade);
        }

        @Test
        @DisplayName("异常：ID 不存在 → 抛 BaseException 含 PROCESS_DEF_NOT_FOUND")
        void getBpmnXml_idNotFound_shouldThrowNotFound() {
            when(mapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> service.getBpmnXml(999L))
                    .isInstanceOf(BaseException.class)
                    .satisfies(e -> {
                        BaseException be = (BaseException) e;
                        assertThat(be.getCode()).isEqualTo(BpmErrorCode.PROCESS_DEF_NOT_FOUND.getCode());
                    });

            verifyNoInteractions(bpmDeployFacade);
        }

        @Test
        @DisplayName("异常：流程 PUBLISHED 但 processDefinitionId 为空 → 抛 BaseException 含 PROCESS_NOT_PUBLISHED")
        void getBpmnXml_publishedButNoDefId_shouldThrowNotPublished() {
            BpmProcessDef def = createPublishedDef();
            def.setProcessDefinitionId(null);
            when(mapper.selectById(3L)).thenReturn(def);

            assertThatThrownBy(() -> service.getBpmnXml(3L))
                    .isInstanceOf(BaseException.class)
                    .satisfies(e -> {
                        BaseException be = (BaseException) e;
                        assertThat(be.getCode()).isEqualTo(BpmErrorCode.PROCESS_NOT_PUBLISHED.getCode());
                    });

            verifyNoInteractions(bpmDeployFacade);
        }
    }

    // ==================== listDefs ====================

    @Nested
    @DisplayName("listDefs 方法（formKey 过滤）")
    class ListDefsTests {

        @BeforeAll
        static void initTableInfo() {
            // LambdaQueryWrapper 解析 SFunction 需要实体 lambda 缓存；纯单测无 MyBatis 上下文，手动初始化
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), BpmProcessDef.class);
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<LambdaQueryWrapper<BpmProcessDef>> captureWrapper(String formKey) {
            PageResult<BpmProcessDef> pageResult = new PageResult<>();
            pageResult.setRecords(List.of());
            pageResult.setTotal(0);
            pageResult.setPageNum(1);
            pageResult.setPageSize(20);
            when(mapper.selectPage(any(PageParam.class), any())).thenReturn(pageResult);

            service.listDefs(new PageParam(), formKey);

            ArgumentCaptor<LambdaQueryWrapper<BpmProcessDef>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(mapper).selectPage(any(PageParam.class), captor.capture());
            return captor;
        }

        @Test
        @DisplayName("传 formKey → 查询条件含 form_key 精确匹配")
        void listDefs_withFormKey_filtersByFormKey() {
            ArgumentCaptor<LambdaQueryWrapper<BpmProcessDef>> captor = captureWrapper("leave_form");

            LambdaQueryWrapper<BpmProcessDef> wrapper = captor.getValue();
            assertThat(wrapper.getSqlSegment()).contains("form_key");
            assertThat(wrapper.getParamNameValuePairs().values()).contains("leave_form");
        }

        @Test
        @DisplayName("不传 formKey → 查询条件不含 form_key（全量列表语义不变）")
        void listDefs_withoutFormKey_noFilter() {
            ArgumentCaptor<LambdaQueryWrapper<BpmProcessDef>> captor = captureWrapper(null);

            LambdaQueryWrapper<BpmProcessDef> wrapper = captor.getValue();
            assertThat(wrapper.getSqlSegment()).doesNotContain("form_key");
        }

        @Test
        @DisplayName("formKey 为空白串 → 视为未过滤")
        void listDefs_blankFormKey_treatedAsNoFilter() {
            ArgumentCaptor<LambdaQueryWrapper<BpmProcessDef>> captor = captureWrapper("   ");

            LambdaQueryWrapper<BpmProcessDef> wrapper = captor.getValue();
            assertThat(wrapper.getSqlSegment()).doesNotContain("form_key");
        }
    }
}
