package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessTemplate;
import com.sw.ck.bpm.process.mapper.BpmProcessTemplateMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I4 §3.2 模板中心行为证据：分级授权（服务端拒绝）、停用拒复制、
 * 复制走正式定义创建链且模板零改写、溯源登记。
 */
class BpmProcessTemplateServiceTest {

    private final BpmProcessTemplateMapper mapper = mock(BpmProcessTemplateMapper.class);
    private final BpmProcessDefService defService = mock(BpmProcessDefService.class);
    private final BpmProcessTemplateServiceImpl service =
            new BpmProcessTemplateServiceImpl(mapper, defService);

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.sw.ck.bpm.process.entity.BpmProcessTemplate.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.sw.ck.bpm.process.entity.BpmProcessDef.class);
    }

    @AfterEach
    void clearHolder() {
        LoginUserHolder.clear();
    }

    private static LoginUser login(Long deptId) {
        LoginUser user = new LoginUser();
        user.setUserId(9L);
        user.setDeptId(deptId);
        user.setSuperAdmin(false);
        return user;
    }

    @Test
    void shouldRejectOutOfScopeTemplateGetAndCopy() {
        LoginUserHolder.set(login(2L)); // 本人部门 2
        BpmProcessTemplate template = template("DEPT", 1L, "ENABLED"); // 模板归属部门 1
        when(mapper.selectById(5L)).thenReturn(template);
        assertThatThrownBy(() -> service.get(5L))
                .isInstanceOf(BaseException.class)
                .hasFieldOrPropertyWithValue("errorKey", "common.forbidden");
        assertThatThrownBy(() -> service.copyToDefinition(5L, "x"))
                .isInstanceOf(BaseException.class);
        verify(defService, never()).createDef(any(), any());
    }

    @Test
    void shouldRejectCopyOfDisabledTemplate() {
        LoginUserHolder.set(login(1L));
        BpmProcessTemplate template = template("DEPT", 1L, "DISABLED");
        when(mapper.selectById(6L)).thenReturn(template);
        assertThatThrownBy(() -> service.copyToDefinition(6L, "x"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("停用");
        verify(defService, never()).createDef(any(), any());
    }

    @Test
    void shouldCopyViaOfficialDefChainAndRegisterTraceability() {
        LoginUserHolder.set(login(1L));
        BpmProcessTemplate template = template("DEPT", 1L, "ENABLED");
        template.setFormKey("leave_form");
        template.setGraphJson("{\"processKey\":\"tpl_key\",\"elements\":[]}");
        when(mapper.selectById(7L)).thenReturn(template);
        BpmProcessDef created = new BpmProcessDef();
        created.setId(100L);
        created.setStatus("DRAFT");
        created.setProcessKey("def_key");
        when(defService.createDef("新定义", "leave_form")).thenReturn(created);
        when(defService.getDef(100L)).thenReturn(created);

        BpmProcessDef result = service.copyToDefinition(7L, "新定义");

        assertThat(result.getId()).isEqualTo(100L);
        verify(defService).createDef("新定义", "leave_form");
        verify(defService).saveDraftGraph(100L, template.getGraphJson());
        verify(defService).markTemplateSource(100L, 5L, template.getTemplateVersion());
        verify(mapper, never()).updateById(any(BpmProcessTemplate.class));
    }

    private static BpmProcessTemplate template(String scopeType, Long scopeDeptId, String status) {
        BpmProcessTemplate template = new BpmProcessTemplate();
        template.setId(5L);
        template.setName("请假模板");
        template.setFormKey("leave_form");
        template.setTemplateVersion(3);
        template.setStatus(status);
        template.setScopeType(scopeType);
        template.setScopeDeptId(scopeDeptId);
        return template;
    }
}
