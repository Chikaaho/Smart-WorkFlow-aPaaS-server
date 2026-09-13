package com.sw.ck.openapi.biz.service;

import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.entity.OpenApiIdempotency;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiIdempotencyMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiNonceMapper;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.facade.FormDataSubmitFacade;
import com.sw.ck.openapi.api.exception.OpenApiErrorCode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I4 §3.4 行为证据：签名校验、时间窗、防重放、scope 拒绝、幂等键只产生一次业务效果、
 * 租户边界状态查询。
 */
class OpenApiServiceTest {

    private final OpenApiAppMapper appMapper = mock(OpenApiAppMapper.class);
    private final OpenApiNonceMapper nonceMapper = mock(OpenApiNonceMapper.class);
    private final OpenApiIdempotencyMapper idemMapper = mock(OpenApiIdempotencyMapper.class);
    private final OpenApiAuthService auth = new OpenApiAuthService(appMapper, nonceMapper);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OpenApiApp.class);
        TableInfoHelper.initTableInfo(assistant, OpenApiIdempotency.class);
    }

    @AfterEach
    void clear() {
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    private OpenApiApp app() {
        OpenApiApp app = new OpenApiApp();
        app.setAppId("ext-app");
        app.setSecretHash(OpenApiAuthService.sha256("secret"));
        app.setScopes("PROCESS_START,PROCESS_QUERY");
        app.setStatus("ENABLED");
        app.setTenantId(1L);
        app.setActAsUserId(8L);
        return app;
    }

    private String[] headers(String body) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "n-" + System.nanoTime();
        String sign = OpenApiAuthService.sign(app().getSecretHash(), "ext-app", ts, nonce, body);
        return new String[]{ts, nonce, sign};
    }

    @Test
    void shouldRejectBadSignatureExpiredWindowReplayAndMissingScope() {
        OpenApiApp app = app();
        when(appMapper.selectOne(any())).thenReturn(app);
        String[] headers = headers("{}");

        assertThatThrownBy(() -> auth.authenticate("ext-app", headers[0], "x1", "deadbeef",
                "{}", "PROCESS_START")).isInstanceOfSatisfying(BaseException.class,
                e -> assertThat(e.getCode()).isEqualTo(OpenApiErrorCode.SIGN_INVALID.getCode()));

        assertThatThrownBy(() -> auth.authenticate("ext-app",
                String.valueOf(System.currentTimeMillis() / 1000 - 4000), "x2",
                OpenApiAuthService.sign(app.getSecretHash(), "ext-app",
                        String.valueOf(System.currentTimeMillis() / 1000 - 4000), "x2", "{}"),
                "{}", "PROCESS_START"))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(OpenApiErrorCode.TIMESTAMP_EXPIRED.getCode()));

        when(nonceMapper.insert(any(com.sw.ck.openapi.biz.entity.OpenApiNonce.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        assertThatThrownBy(() -> auth.authenticate("ext-app", headers[0], headers[1], headers[2],
                "{}", "PROCESS_START")).isInstanceOfSatisfying(BaseException.class,
                e -> assertThat(e.getCode()).isEqualTo(OpenApiErrorCode.NONCE_REUSED.getCode()));

        when(nonceMapper.insert(any(com.sw.ck.openapi.biz.entity.OpenApiNonce.class))).thenReturn(1);
        assertThatThrownBy(() -> auth.authenticate("ext-app", headers[0], "fresh-nonce",
                OpenApiAuthService.sign(app.getSecretHash(), "ext-app", headers[0],
                        "fresh-nonce", "{}"), "{}", "TASK_HANDLE"))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(OpenApiErrorCode.SCOPE_DENIED.getCode()));
    }

    @Test
    void shouldReplayIdempotentStartWithoutSecondSubmit() {
        LoginUserHolderContext.set(1L);
        FormDataSubmitFacade submitFacade = mock(FormDataSubmitFacade.class);
        OpenApiProcessService service = new OpenApiProcessService(submitFacade,
                mock(BpmTaskFacade.class), mock(BpmRuntimeFacade.class), idemMapper);
        OpenApiAuthService.OpenApiAuthContext context = new OpenApiAuthService.OpenApiAuthContext(
                "ext-app", 1L, 8L, List.of("PROCESS_START"));

        when(idemMapper.selectOne(any())).thenReturn(null);
        when(submitFacade.submit(anyString(), any(), contains("BK:record-1")))
                .thenReturn("record-1");

        Map<String, Object> first = service.start(context, "leave_form", Map.of("days", 1),
                "record-1", null);
        assertThat(first).containsEntry("recordId", "record-1")
                .containsEntry("idempotentReplay", false);

        when(idemMapper.selectOne(any())).thenReturn(idemRow("record-1"));
        Map<String, Object> second = service.start(context, "leave_form", Map.of("days", 1),
                "record-1", null);
        assertThat(second).containsEntry("idempotentReplay", true);
    }

    @Test
    void shouldDenyCrossTenantStatusQuery() {
        BpmTaskFacade taskFacade = mock(BpmTaskFacade.class);
        BpmRuntimeFacade runtimeFacade = mock(BpmRuntimeFacade.class);
        OpenApiProcessService service = new OpenApiProcessService(
                mock(FormDataSubmitFacade.class), taskFacade, runtimeFacade, idemMapper);
        OpenApiAuthService.OpenApiAuthContext context = new OpenApiAuthService.OpenApiAuthContext(
                "ext-app", 1L, 8L, List.of("PROCESS_QUERY"));
        when(taskFacade.getBusinessKey("pi-x")).thenReturn("record-9");
        when(runtimeFacade.getProcessVariables("pi-x")).thenReturn(Map.of("tenantId", 2L));

        assertThatThrownBy(() -> service.status(context, "pi-x"))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(OpenApiErrorCode.PROCESS_NOT_VISIBLE.getCode()));
    }

    private static OpenApiIdempotency idemRow(String ref) {
        OpenApiIdempotency row = new OpenApiIdempotency();
        row.setAppId("ext-app");
        row.setIdemKey("BK:record-1:ext-app");
        row.setResultRef(ref);
        return row;
    }

    /** 占位类型：保持 import 简洁。 */
    private static final class LoginUserHolderContext {
        static void set(Long tenantId) {
            com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
            user.setUserId(8L);
            user.setTenantId(tenantId);
            com.sw.ck.security.holder.LoginUserHolder.set(user);
        }
    }
}
