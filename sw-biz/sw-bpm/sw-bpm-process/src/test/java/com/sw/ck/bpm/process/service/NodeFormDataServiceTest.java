package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmProcessDefVersionMapper;
import com.sw.ck.bpm.process.mapper.BpmTaskFormDataMapper;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NodeFormDataService} 节点业务表单校验与轮次单测（ADR-P64-001 §1）。
 */
@DisplayName("P64 节点业务表单数据服务测试")
class NodeFormDataServiceTest {

    private final BpmTaskFormDataMapper taskFormDataMapper = mock(BpmTaskFormDataMapper.class);
    private final BpmProcessDefService bpmProcessDefService = mock(BpmProcessDefService.class);
    private final BpmProcessDefVersionMapper versionMapper = mock(BpmProcessDefVersionMapper.class);
    private final FormDefinitionService formDefinitionService = mock(FormDefinitionService.class);
    private final ApprovalActionService approvalActionService = mock(ApprovalActionService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UserQueryFacade> userProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DeptQueryFacade> deptProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DictFacade> dictProvider = mock(ObjectProvider.class);
    private final UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);

    private final NodeFormDataService service = new NodeFormDataService(
            taskFormDataMapper, bpmProcessDefService, versionMapper, formDefinitionService,
            approvalActionService, userProvider, deptProvider, dictProvider, new ObjectMapper());

    @BeforeEach
    void setUpTenant() {
        com.sw.ck.security.holder.LoginUser loginUser = new com.sw.ck.security.holder.LoginUser();
        loginUser.setUserId(2L);
        loginUser.setTenantId(9L);
        com.sw.ck.security.holder.LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void clearTenant() {
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    private static final String QC_DEFINITION = "{\"fields\":["
            + "{\"name\":\"verdict\",\"type\":\"DICT\",\"dictType\":\"qc_verdict\",\"required\":true},"
            + "{\"name\":\"ng_count\",\"type\":\"NUMBER\",\"required\":true},"
            + "{\"name\":\"handlers\",\"type\":\"USER\",\"multiple\":true},"
            + "{\"name\":\"note\",\"type\":\"TEXT\"},"
            + "{\"name\":\"defects\",\"type\":\"TABLE\",\"subFields\":["
            + "{\"name\":\"code\",\"type\":\"TEXT\",\"required\":true}]}]}";

    private void stubForm() {
        FormDefDTO def = new FormDefDTO();
        def.setFormKey("qc_form");
        def.setStatus("PUBLISHED");
        def.setFormVersion(3);
        when(formDefinitionService.getFormDef("qc_form")).thenReturn(Optional.of(def));
        when(formDefinitionService.getFormDefinition("qc_form"))
                .thenReturn(Optional.of(QC_DEFINITION));
    }

    @Test
    @DisplayName("必填缺失/未知字段：校验拒绝并定位字段")
    void shouldRejectMissingRequiredAndUnknownFields() {
        stubForm();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ghost", "x");

        List<String> errors = service.validateNodeFormData("qc_form", data);

        assertThat(errors).anyMatch(error -> error.contains("verdict: 必填"));
        assertThat(errors).anyMatch(error -> error.contains("ng_count: 必填"));
        assertThat(errors).anyMatch(error -> error.contains("未知字段: ghost"));
    }

    @Test
    @DisplayName("类型/字典值域/USER 对象存在性校验")
    void shouldValidateTypesAndObjects() {
        stubForm();
        when(userProvider.getIfAvailable()).thenReturn(userQueryFacade);
        when(userQueryFacade.findActiveUserIds(any(), eq(9L)))
                .thenReturn(Optional.of(List.of(11L)));
        com.sw.ck.system.api.dict.DictFacade dictFacade = mock(com.sw.ck.system.api.dict.DictFacade.class);
        when(dictProvider.getIfAvailable()).thenReturn(dictFacade);
        when(dictFacade.isValidCode(eq("qc_verdict"), eq("PASS"))).thenReturn(Optional.of(true));
        when(dictFacade.isValidCode(eq("qc_verdict"), eq("NOT_IN_DICT"))).thenReturn(Optional.of(false));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict", "NOT_IN_DICT");
        data.put("ng_count", "abc");
        data.put("handlers", List.of("11", "999"));
        data.put("note", "ok");

        List<String> errors = service.validateNodeFormData("qc_form", data);

        assertThat(errors).anyMatch(error -> error.contains("ng_count: 必须是数字"));
        assertThat(errors).anyMatch(error -> error.contains("verdict: 字典值超出值域"));
        assertThat(errors).anyMatch(error -> error.contains("handlers: 含不存在或已停用的用户"));
        assertThat(errors).noneMatch(error -> error.contains("note"));
    }

    @Test
    @DisplayName("TABLE 子行必填校验")
    void shouldValidateTableSubRows() {
        stubForm();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict", "PASS");
        data.put("ng_count", 0);
        data.put("defects", List.of(Map.of("code", "D1"), Map.of("other", "x")));

        List<String> errors = service.validateNodeFormData("qc_form", data);

        assertThat(errors).anyMatch(error -> error.contains("defects.code: 第2行必填"));
    }

    @Test
    @DisplayName("合法数据零错误（USER 全部有效）")
    void shouldPassValidData() {
        stubForm();
        when(userProvider.getIfAvailable()).thenReturn(userQueryFacade);
        when(userQueryFacade.findActiveUserIds(any(), eq(9L)))
                .thenReturn(Optional.of(List.of(11L, 12L)));
        when(dictProvider.getIfAvailable()).thenReturn(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict", "PASS");
        data.put("ng_count", 0);
        data.put("handlers", List.of("11", "12"));

        assertThat(service.validateNodeFormData("qc_form", data)).isEmpty();
    }

    @Test
    @DisplayName("当前轮次 = RETURN 动作数 + 1（退回旧轮不混入新轮）")
    void shouldComputeRoundFromReturnCount() {
        com.sw.ck.bpm.process.entity.ApprovalActionRecord returnRecord =
                new com.sw.ck.bpm.process.entity.ApprovalActionRecord();
        returnRecord.setAction("RETURN");
        com.sw.ck.bpm.process.entity.ApprovalActionRecord approveRecord =
                new com.sw.ck.bpm.process.entity.ApprovalActionRecord();
        approveRecord.setAction("APPROVE");
        when(approvalActionService.findByProcessInstanceId("pi-1"))
                .thenReturn(List.of(approveRecord, returnRecord));

        assertThat(service.currentRound("pi-1")).isEqualTo(2L);
    }

    @Test
    @DisplayName("loadGraph：defVersion 命中冻结版本行时按冻结图解析")
    void shouldLoadFrozenGraphByVersion() throws Exception {
        BpmProcessDef def = new BpmProcessDef();
        def.setId(5L);
        def.setProcessKey("def_main");
        def.setGraphJson("{\"processKey\":\"current-draft\"}");
        when(bpmProcessDefService.findByProcessKey("def_main")).thenReturn(def);
        com.sw.ck.bpm.process.entity.BpmProcessDefVersion version =
                new com.sw.ck.bpm.process.entity.BpmProcessDefVersion();
        version.setDefId(5L);
        version.setGraphVersion(2);
        version.setGraphJson("{\"processKey\":\"frozen-v2\"}");
        when(versionMapper.selectOne(any())).thenReturn(version);

        com.sw.ck.bpm.api.dto.ProcessGraph graph = service.loadGraph("def_main", 2);
        assertThat(graph.getProcessKey()).isEqualTo("frozen-v2");

        com.sw.ck.bpm.api.dto.ProcessGraph fallback = service.loadGraph("def_main", null);
        assertThat(fallback.getProcessKey()).isEqualTo("current-draft");
    }
}
