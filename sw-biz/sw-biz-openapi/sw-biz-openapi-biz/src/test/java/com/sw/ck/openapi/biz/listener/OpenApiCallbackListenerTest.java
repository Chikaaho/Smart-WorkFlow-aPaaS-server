package com.sw.ck.openapi.biz.listener;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.common.tenant.TenantWorkAdmission;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.service.OpenApiCallbackDeliveryService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 0.1.7 追加缺陷 · 异步回调监听器的租户身份还原语义（确定性单测）。
 *
 * <p>生产调用链为 {@code @Async @TransactionalEventListener}：线程无请求身份。本用例在
 * "无线程身份"的测试线程上直接调用方法体，断言投递在事件权威租户身份下执行且用毕清理；
 * 缺失/无效身份与非终态事件不触达任何查询与投递。</p>
 */
@DisplayName("0.1.7 追加缺陷 · 回调监听器身份还原：事件租户执行 + 用毕清理 + 拒绝语义")
@ExtendWith(MockitoExtension.class)
class OpenApiCallbackListenerTest {

    @Mock
    private OpenApiAppMapper appMapper;

    @Mock
    private OpenApiCallbackDeliveryService deliveryService;

    @Mock
    private TenantWorkAdmission tenantWorkAdmission;

    private OpenApiCallbackListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(tenantWorkAdmission.claim(any(), any())).thenAnswer(invocation ->
                invocation.getArgument(1, java.util.function.IntSupplier.class).getAsInt());
        listener = new OpenApiCallbackListener(appMapper, deliveryService, tenantWorkAdmission);
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("无请求线程身份：以事件租户与操作人还原执行身份，finally 清理不残留")
    void onEvent_shouldRestoreEventTenantIdentityAndCleanUp() {
        OpenApiApp app = new OpenApiApp();
        app.setAppId("oc-unit-app");
        app.setTenantId(9001L);
        when(appMapper.selectList(any())).thenReturn(List.of(app));
        AtomicReference<LoginUser> seen = new AtomicReference<>();
        doAnswer(invocation -> {
            seen.set(LoginUserHolder.get());
            return null;
        }).when(deliveryService).dispatch(any(), any());

        assertThat(LoginUserHolder.get()).as("前置：测试线程无请求身份").isNull();
        listener.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, 77L, 9001L, 55L, "oc-unit-1"));

        assertThat(seen.get()).as("投递执行期间存在还原身份").isNotNull();
        assertThat(seen.get().getTenantId()).as("身份租户=事件权威租户").isEqualTo(9001L);
        assertThat(seen.get().getUserId()).as("身份用户=事件操作人（供审计列注入）").isEqualTo(55L);
        assertThat(LoginUserHolder.get()).as("用毕清理不残留").isNull();
    }

    @Test
    @DisplayName("租户停用或过期：不查询应用、不投递且不残留身份")
    void inactiveTenantStopsBeforeCallbackLookup() {
        doReturn(0).when(tenantWorkAdmission).claim(any(), any());

        listener.onEvent(new BpmNotifyEvent(
                BpmNotifyTrigger.PROCESS_APPROVED, 77L, 9001L, 55L, "oc-paused-1"));

        verifyNoInteractions(appMapper, deliveryService);
        assertThat(LoginUserHolder.get()).isNull();
    }

    @Test
    @DisplayName("操作人缺失：用户身份按系统操作员兜底，租户仍为事件租户")
    void onEvent_shouldFallbackToSystemOperatorWhenActorMissing() {
        OpenApiApp app = new OpenApiApp();
        app.setAppId("oc-unit-app");
        app.setTenantId(0L);
        when(appMapper.selectList(any())).thenReturn(List.of(app));
        AtomicReference<LoginUser> seen = new AtomicReference<>();
        doAnswer(invocation -> {
            seen.set(LoginUserHolder.get());
            return null;
        }).when(deliveryService).dispatch(any(), any());

        listener.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, 77L, 0L, null, "oc-unit-2"));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getTenantId()).isEqualTo(0L);
        assertThat(seen.get().getUserId()).as("系统操作员兜底").isEqualTo(0L);
        assertThat(LoginUserHolder.get()).isNull();
    }

    @Test
    @DisplayName("缺失租户身份：拒绝处理，零查库零投递（不退化为全局查询）")
    void onEvent_shouldRefuseMissingTenantIdentity() {
        listener.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, 1L, null, 1L, "oc-null"));

        verifyNoInteractions(appMapper);
        verifyNoInteractions(deliveryService);
        assertThat(LoginUserHolder.get()).isNull();
    }

    @Test
    @DisplayName("非终态/缺业务ID/空事件：不触达查询与投递")
    void onEvent_shouldSkipNonTerminalAndIncompleteEvents() {
        listener.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.TODO_CREATED, 1L, 0L, 1L, "task-1"));
        listener.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, 1L, 0L, 1L, null));
        listener.onEvent(null);

        verifyNoInteractions(appMapper);
        verifyNoInteractions(deliveryService);
    }
}
