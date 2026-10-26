package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.integration.ExternalCapability;
import com.sw.ck.common.integration.ExternalCapabilityFacade;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.dto.FormIntegrationInvokeReq;
import com.sw.ck.form.dto.FormIntegrationInvokeResult;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormIntegrationInvocationEntity;
import com.sw.ck.form.mapper.FormIntegrationInvocationMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 表单受控集成授权交集、阶段边界与写副作用幂等。
 */
class FormIntegrationServiceTest {

    private FormDefService formDefService;
    private FormDataQueryService formDataQueryService;
    private ExternalCapabilityFacade facade;
    private FormIntegrationInvocationMapper invocationMapper;
    private JdbcTemplate jdbcTemplate;
    private FormIntegrationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        formDefService = mock(FormDefService.class);
        formDataQueryService = mock(FormDataQueryService.class);
        facade = mock(ExternalCapabilityFacade.class);
        invocationMapper = mock(FormIntegrationInvocationMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        ObjectProvider<ExternalCapabilityFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facade);
        service = new FormIntegrationService(formDefService, formDataQueryService, provider,
                invocationMapper, new FormIdGenerator(), jdbcTemplate, new ObjectMapper());

        LoginUser user = new LoginUser();
        user.setUserId(7L);
        user.setTenantId(1L);
        LoginUserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("查询能力按版本快照允许清单调用并返回真实结果")
    void queryCapabilityRequiresSnapshotAllowlist() {
        stubForm("{\"fields\":[],\"allowedIntegrations\":[\"cap_q\"]}");
        when(facade.findEnabled(1L, "cap_q"))
                .thenReturn(new ExternalCapability("cap_q", "query", "QUERY", 30, 1L));
        when(facade.invoke(any(), anyString())).thenReturn("{\"echo\":\"ok\"}");

        FormIntegrationInvokeResult result = service.invoke(req("cap_q", "BEFORE_SUBMIT", null, null));

        assertEquals("QUERY", result.getEffectScope());
        assertEquals("ok", ((com.fasterxml.jackson.databind.JsonNode) result.getResult())
                .get("echo").asText());
        verify(invocationMapper, never()).insert(any(FormIntegrationInvocationEntity.class));
    }

    @Test
    @DisplayName("不在绑定版本允许清单内直接拒绝")
    void rejectsCapabilityNotInSnapshot() {
        stubForm("{\"fields\":[],\"allowedIntegrations\":[\"cap_other\"]}");

        BaseException error = assertThrows(BaseException.class,
                () -> service.invoke(req("cap_q", "BEFORE_SUBMIT", null, null)));

        assertEquals(FormErrorCode.INTEGRATION_NOT_ALLOWED.getCode(), error.getCode());
        verify(facade, never()).invoke(any(), anyString());
    }

    @Test
    @DisplayName("未登记、停用、跨租户或未分类能力不可用")
    void rejectsUnavailableCapability() {
        stubForm("{\"fields\":[],\"allowedIntegrations\":[\"cap_q\"]}");
        when(facade.findEnabled(1L, "cap_q")).thenReturn(null);

        BaseException error = assertThrows(BaseException.class,
                () -> service.invoke(req("cap_q", "BEFORE_SUBMIT", null, null)));

        assertEquals(FormErrorCode.INTEGRATION_UNAVAILABLE.getCode(), error.getCode());
    }

    @Test
    @DisplayName("写副作用能力只允许提交后阶段")
    void writeCapabilityRejectedBeforeSubmit() {
        stubForm("{\"fields\":[],\"allowedIntegrations\":[\"cap_w\"]}");
        when(facade.findEnabled(1L, "cap_w"))
                .thenReturn(new ExternalCapability("cap_w", "write", "WRITE", 30, 1L));

        BaseException error = assertThrows(BaseException.class,
                () -> service.invoke(req("cap_w", "BEFORE_SUBMIT", "rec-1", "rec-1")));

        assertEquals(FormErrorCode.INTEGRATION_PHASE_DENIED.getCode(), error.getCode());
        verify(facade, never()).invoke(any(), anyString());
    }

    @Test
    @DisplayName("提交后写副作用按提交标识幂等，重复调用返回原结果")
    void writeCapabilityIsIdempotent() {
        stubForm("{\"fields\":[],\"allowedIntegrations\":[\"cap_w\"]}");
        when(facade.findEnabled(1L, "cap_w"))
                .thenReturn(new ExternalCapability("cap_w", "write", "WRITE", 30, 1L));
        when(facade.invoke(any(), anyString())).thenReturn("{\"count\":1}");
        when(formDataQueryService.getRecordDetail("u020_form", "rec-1")).thenReturn(Map.of());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(List.of(7L));
        when(invocationMapper.selectList(any())).thenReturn(List.of());
        when(invocationMapper.insert(any(FormIntegrationInvocationEntity.class))).thenReturn(1);
        when(invocationMapper.updateById(any(FormIntegrationInvocationEntity.class))).thenReturn(1);

        FormIntegrationInvokeResult first =
                service.invoke(req("cap_w", "AFTER_SUBMIT", "rec-1", "rec-1"));
        assertTrue(!first.isReplayed());
        assertEquals(1, ((com.fasterxml.jackson.databind.JsonNode) first.getResult())
                .get("count").asInt());

        FormIntegrationInvocationEntity succeeded = new FormIntegrationInvocationEntity();
        succeeded.setStatus("SUCCEEDED");
        succeeded.setResultJson("{\"count\":1}");
        when(invocationMapper.selectList(any())).thenReturn(List.of(succeeded));

        FormIntegrationInvokeResult replay =
                service.invoke(req("cap_w", "AFTER_SUBMIT", "rec-1", "rec-1"));
        assertTrue(replay.isReplayed());
        assertEquals(1, ((com.fasterxml.jackson.databind.JsonNode) replay.getResult())
                .get("count").asInt());
    }

    @Test
    @DisplayName("同一提交标识携带不同输入时拒绝，不能返回旧结果")
    void writeCapabilityRejectsChangedPayloadForSameKey() {
        stubForm("{\"fields\":[],\"allowedIntegrations\":[\"cap_w\"]}");
        when(facade.findEnabled(1L, "cap_w"))
                .thenReturn(new ExternalCapability("cap_w", "write", "WRITE", 30, 1L));
        when(formDataQueryService.getRecordDetail("u020_form", "rec-1")).thenReturn(Map.of());
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(List.of(7L));
        FormIntegrationInvocationEntity existing = new FormIntegrationInvocationEntity();
        existing.setStatus("SUCCEEDED");
        existing.setRequestHash("different-payload-hash");
        existing.setResultJson("{\"count\":1}");
        when(invocationMapper.selectList(any())).thenReturn(List.of(existing));

        BaseException error = assertThrows(BaseException.class,
                () -> service.invoke(req("cap_w", "AFTER_SUBMIT", "rec-1", "rec-1")));

        assertEquals(FormErrorCode.INTEGRATION_IDEMPOTENCY_CONFLICT.getCode(), error.getCode());
        verify(facade, never()).invoke(any(), anyString());
    }

    @Test
    @DisplayName("提交后写副作用要求当前主体是记录提交人")
    void writeCapabilityRequiresSubmitter() {
        stubForm("{\"fields\":[],\"allowedIntegrations\":[\"cap_w\"]}");
        when(facade.findEnabled(1L, "cap_w"))
                .thenReturn(new ExternalCapability("cap_w", "write", "WRITE", 30, 1L));
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(List.of(99L));

        BaseException error = assertThrows(BaseException.class,
                () -> service.invoke(req("cap_w", "AFTER_SUBMIT", "rec-1", "rec-1")));

        assertEquals(FormErrorCode.INTEGRATION_RECORD_DENIED.getCode(), error.getCode());
        verify(facade, never()).invoke(any(), anyString());
    }

    private void stubForm(String definition) {
        FormDefDTO dto = new FormDefDTO();
        dto.setId("form-1");
        dto.setFormKey("u020_form");
        dto.setFormVersion(3);
        when(formDefService.isCurrentUserVisible("u020_form")).thenReturn(true);
        when(formDefService.getFormDefByKey("u020_form")).thenReturn(dto);
        when(formDefService.getSnapshotDefinitionByKey("u020_form", 3)).thenReturn(definition);
    }

    private FormIntegrationInvokeReq req(String capabilityId, String phase,
                                         String recordId, String requestKey) {
        FormIntegrationInvokeReq req = new FormIntegrationInvokeReq();
        req.setFormKey("u020_form");
        req.setCapabilityId(capabilityId);
        req.setPhase(phase);
        req.setRecordId(recordId);
        req.setRequestKey(requestKey);
        req.setInput(Map.of("source", "form"));
        return req;
    }
}
