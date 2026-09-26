package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.event.BpmDeviceCommandEvent;
import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 5 · 审批设备命令 AFTER_COMMIT 监听器的职责边界（单元级正反断言）。
 *
 * <p>边界抽取后该监听器只通过 {@link IotDeviceFacade} 契约交互，不再触碰 IoT 实现模块的
 * entity/mapper：意图已在审批事务内由 {@link BpmDeviceCommandIntentRecorder} 持久化，
 * 发送失败与重试由 IoT 实现模块的补偿调度承担。因此本类的断言是：</p>
 * <ol>
 *   <li><b>结构</b>：监听器不持有任何 IoT 实现包（entity/mapper/service/job）协作者；</li>
 *   <li><b>行为</b>：契约返回 empty（不适用）时不抛出、不伪造受理成功；</li>
 *   <li><b>行为</b>：契约抛异常时不向调用方传播——审批事务已提交，补偿路径失败不得反向影响审批；</li>
 *   <li><b>行为</b>：契约返回 present 时正常消费结果。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BpmDeviceCommandListenerTest {

    /** IoT 实现模块的包前缀：出现在监听器协作者类型上即为跨模块实现泄漏。 */
    private static final Set<String> IOT_IMPLEMENTATION_PACKAGE_PREFIXES = Set.of(
            "com.sw.ck.iot.entity",
            "com.sw.ck.iot.mapper",
            "com.sw.ck.iot.service",
            "com.sw.ck.iot.job",
            "com.sw.ck.iot.script",
            "com.sw.ck.iot.mqtt",
            "com.sw.ck.iot.provider",
            "com.sw.ck.iot.controller",
            "com.sw.ck.iot.config");

    @Mock
    private IotDeviceFacade iotDeviceFacade;

    private BpmDeviceCommandListener listener;

    @BeforeEach
    void setUp() {
        LoginUserHolder.clear();
    }

    /** 每个用例按需装配，避免未使用的桩（Mockito 严格模式）。 */
    private BpmDeviceCommandListener listenerWith(IotDeviceFacade facade) {
        @SuppressWarnings("unchecked")
        ObjectProvider<IotDeviceFacade> facadeProvider = mock(ObjectProvider.class);
        when(facadeProvider.getIfAvailable()).thenReturn(facade);
        this.listener = new BpmDeviceCommandListener(facadeProvider);
        return this.listener;
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("结构：监听器不持有任何 IoT 实现包协作者（entity/mapper/service/job 全不可达）")
    void listenerHoldsNoIotImplementationCollaborator() throws Exception {
        for (Field field : BpmDeviceCommandListener.class.getDeclaredFields()) {
            String typeName = field.getType().getName();
            boolean leaked = IOT_IMPLEMENTATION_PACKAGE_PREFIXES.stream().anyMatch(typeName::startsWith);
            assertFalse(leaked, "监听器字段不得引用 IoT 实现类型: " + field.getName() + " -> " + typeName);
        }
        // 构造器只接受契约门面提供者：没有任何可写入 IoT 实现表的协作者
        long constructorParams = java.util.Arrays.stream(BpmDeviceCommandListener.class.getDeclaredConstructors())
                .mapToLong(java.lang.reflect.Constructor::getParameterCount)
                .sum();
        assertEquals(1L, constructorParams, "监听器只应依赖 1 个契约门面提供者");
    }

    @Test
    @DisplayName("行为：契约返回 empty（设备不适用）时不抛出、不伪造受理成功")
    void emptyOutcomeDoesNotThrow() {
        when(iotDeviceFacade.dispatchCommandIdempotent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        BpmDeviceCommandListener listener = listenerWith(iotDeviceFacade);
        BpmDeviceCommandEvent event = new BpmDeviceCommandEvent("process-empty-1", "prod-001", "dev-001",
                "power_on", "PROPERTY", 100L, 200L);

        assertDoesNotThrow(() -> listener.onProcessApproved(event),
                "AFTER_COMMIT 补偿路径不得因不适用而抛出");
        verify(iotDeviceFacade, times(1)).dispatchCommandIdempotent(
                eq("prod-001"), eq("dev-001"), eq("power_on"), eq("PROPERTY"),
                isNull(), eq("process-empty-1"), any());
    }

    @Test
    @DisplayName("行为：下发抛异常时不向调用方传播，审批事务已提交不受影响")
    void dispatchFailureDoesNotPropagate() {
        // 下发侧失败：不得被本层吞掉后改写为"成功"，也不得抛出影响已提交审批
        when(iotDeviceFacade.dispatchCommandIdempotent(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("腾讯云 API 调用失败: 设备响应超时"));

        BpmDeviceCommandListener listener = listenerWith(iotDeviceFacade);
        BpmDeviceCommandEvent event = new BpmDeviceCommandEvent("process-abc-123", "prod-001", "dev-001",
                "power_on", "PROPERTY", 100L, 200L);

        assertDoesNotThrow(() -> listener.onProcessApproved(event),
                "审批事务已提交；补偿路径异常不得向外传播");
        verify(iotDeviceFacade, times(1)).dispatchCommandIdempotent(
                eq("prod-001"), eq("dev-001"), eq("power_on"), eq("PROPERTY"),
                isNull(), eq("process-abc-123"), any());
    }

    @Test
    @DisplayName("行为：契约返回命令 ID 时正常消费结果并保持幂等键稳定")
    void presentOutcomeIsConsumed() {
        when(iotDeviceFacade.dispatchCommandIdempotent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(4242L));

        BpmDeviceCommandListener listener = listenerWith(iotDeviceFacade);
        BpmDeviceCommandEvent event = new BpmDeviceCommandEvent("process-ok-1", "prod-002", "dev-002",
                "set_temp", "ACTION", 101L, 201L);

        assertDoesNotThrow(() -> listener.onProcessApproved(event));
        verify(iotDeviceFacade, times(1)).dispatchCommandIdempotent(
                eq("prod-002"), eq("dev-002"), eq("set_temp"), eq("ACTION"), isNull(),
                eq("process-ok-1"),
                eq(BpmDeviceCommandIntentRecorder.idempotentKey(event)));
    }

    @Test
    @DisplayName("行为：门面未装配时记录后返回，不抛出也不触碰任何持久层")
    void missingFacadeIsTolerated() {
        @SuppressWarnings("unchecked")
        ObjectProvider<IotDeviceFacade> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        BpmDeviceCommandListener tolerant = new BpmDeviceCommandListener(emptyProvider);

        BpmDeviceCommandEvent event = new BpmDeviceCommandEvent("process-none-1", "prod-003", "dev-003",
                "power_on", "PROPERTY", 102L, 202L);

        assertDoesNotThrow(() -> tolerant.onProcessApproved(event));
        verifyNoInteractions(iotDeviceFacade);
    }
}
