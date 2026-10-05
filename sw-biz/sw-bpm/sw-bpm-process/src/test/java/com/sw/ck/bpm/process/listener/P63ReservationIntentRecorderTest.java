package com.sw.ck.bpm.process.listener;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.iot.api.IotDeviceFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P63 成功结束预约意图登记器行为证据：仅成功终态产生预约、配置/时刻/设备/参数冻结、
 * 显式时区与不存在/歧义时间拒绝、意图失败使审批整体回滚（fail closed）、IMMEDIATE 零改动。
 */
class P63ReservationIntentRecorderTest {

    private BpmInstanceMapper instanceMapper;
    private BpmProcessDefMapper processDefMapper;
    private BpmRuntimeFacade runtimeFacade;
    private IotCommandReservationFacade reservationFacade;
    private IotDeviceFacade deviceFacade;
    private BpmIotReservationIntentRecorder recorder;

    /** createIntent 16 参捕获（拍平契约的参数断言载体）。 */
    private final java.util.concurrent.atomic.AtomicReference<Object[]> capturedArgs =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                BpmInstance.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                BpmProcessDef.class);
    }

    @BeforeEach
    @AfterEach
    void txState() {
        // 事件在审批事务内发布：测试显式声明事务可见性
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(false);
    }

    private void inTx(Runnable action) {
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
        try {
            action.run();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
        };
    }

    private BpmInstance instance() {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId("pi-1");
        instance.setProcessDefKey("p63_flow");
        instance.setDefVersion(1);
        instance.setFormKey("p63_form");
        instance.setBusinessKey("rec-1");
        return instance;
    }

    private BpmProcessDef def(String actionJson) {
        BpmProcessDef def = new BpmProcessDef();
        def.setProcessKey("p63_flow");
        def.setIotDeviceActionJson(actionJson);
        return def;
    }

    private String configJson(String extra) {
        return JSON.toJSONString(Map.of(
                "enabled", true,
                "deliveryMode", "RESERVATION",
                "deviceSource", "FIXED",
                "deviceId", 33L,
                "commandKey", "power_off",
                "paramField", "power_level",
                "reservation", JSON.parseObject(extra)));
    }

    private void wire(String actionJson, Map<String, Object> formData) {
        instanceMapper = mock(BpmInstanceMapper.class);
        processDefMapper = mock(BpmProcessDefMapper.class);
        runtimeFacade = mock(BpmRuntimeFacade.class);
        reservationFacade = mock(IotCommandReservationFacade.class);
        deviceFacade = mock(IotDeviceFacade.class);
        when(instanceMapper.selectOne(any())).thenReturn(instance());
        when(processDefMapper.selectOne(any())).thenReturn(def(actionJson));
        when(runtimeFacade.getProcessVariables("pi-1")).thenReturn(Optional.of(Map.of(
                "formData", formData)));
        when(deviceFacade.resolveDeviceTarget(100L, 33L)).thenReturn(Optional.of(
                new IotDeviceFacade.DeviceTarget("dev-key-1", "prod-1", "dev-1")));
        when(reservationFacade.createIntent(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    capturedArgs.set(inv.getArguments());
                    return Optional.of(77L);
                });
        recorder = new BpmIotReservationIntentRecorder(instanceMapper, processDefMapper,
                runtimeFacade, provider(reservationFacade), provider(deviceFacade));
    }

    private BpmNotifyEvent approved() {
        return new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, 5L, 100L, 9L, "pi-1");
    }

    @Test
    @DisplayName("成功终态：冻结设备/时刻（显式时区）/参数并在同事务创建意图；非成功终态与 IMMEDIATE 零预约")
    void createsIntentOnlyOnApprovedReservationMode() {
        String config = configJson("{\"dueField\":\"plan_time\",\"timezoneId\":\"Asia/Shanghai\","
                + "\"lateWindowSeconds\":120}");
        wire(config, Map.of("plan_time", "2026-10-20 14:30", "power_level", 3));
        inTx(() -> recorder.onProcessApproved(approved()));

        Object[] a = capturedArgs.get();
        assertThat(a).isNotNull();
        assertThat((String) a[1]).isEqualTo("pi-1");
        assertThat((String) a[6]).isEqualTo("dev-key-1");
        assertThat((String) a[7]).isEqualTo("prod-1");
        assertThat((String) a[9]).isEqualTo("power_off");
        assertThat((String) a[11]).isEqualTo("3"); // 参数载荷=字段值序列化（沿既有 runDeviceAction 口径）
        assertThat((String) a[13]).isEqualTo("Asia/Shanghai");
        assertThat((Integer) a[15]).isEqualTo(120);
        // UTC 时刻 = 本地 14:30（UTC+8）− 8h = 06:30
        assertThat(((java.time.LocalDateTime) a[12]).toLocalTime().toString()).startsWith("06:30");

        // 非成功终态零预约（同一装配，无新增意图）
        inTx(() -> recorder.onProcessApproved(new BpmNotifyEvent(
                BpmNotifyTrigger.PROCESS_REJECTED, 5L, 100L, 9L, "pi-1")));
        verify(reservationFacade, times(1)).createIntent(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

        // IMMEDIATE（无 deliveryMode）零预约（重新装配后无任何意图）
        wire(JSON.toJSONString(Map.of("enabled", true, "deviceSource", "FIXED", "deviceId", 33L)),
                Map.of("plan_time", "2026-10-20 14:30"));
        inTx(() -> recorder.onProcessApproved(approved()));
        verify(reservationFacade, never()).createIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("预约时刻缺失/格式非法/时区时间不存在 → fail closed（审批整体回滚），零意图")
    void invalidFreezeElementsFailClosed() {
        wire(configJson("{\"dueField\":\"plan_time\",\"timezoneId\":\"Asia/Shanghai\"}"),
                Map.of("power_level", 3));
        inTx(() -> assertThatThrownBy(() -> recorder.onProcessApproved(approved()))
                .isInstanceOf(BaseException.class));
        verify(reservationFacade, never()).createIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

        wire(configJson("{\"dueField\":\"plan_time\",\"timezoneId\":\"Asia/Shanghai\"}"),
                Map.of("plan_time", "不是时间"));
        inTx(() -> assertThatThrownBy(() -> recorder.onProcessApproved(approved()))
                .isInstanceOf(BaseException.class));

        // 时区时间不存在（夏令时间隙）：America/New_York 2026-03-08 02:30 在春季前拨中不存在
        wire(configJson("{\"dueField\":\"plan_time\",\"timezoneId\":\"America/New_York\"}"),
                Map.of("plan_time", "2026-03-08 02:30"));
        inTx(() -> assertThatThrownBy(() -> recorder.onProcessApproved(approved()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不存在"));
        verify(reservationFacade, never()).createIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("意图门面不可用 → fail closed；FIXED 设备不存在 → fail closed")
    void missingFacadeOrDeviceFailsClosed() {
        String config = configJson("{\"dueField\":\"plan_time\",\"timezoneId\":\"Asia/Shanghai\"}");
        wire(config, Map.of("plan_time", "2026-10-20 14:30"));
        recorder = new BpmIotReservationIntentRecorder(instanceMapper, processDefMapper,
                runtimeFacade, provider(null), provider(deviceFacade));
        inTx(() -> assertThatThrownBy(() -> recorder.onProcessApproved(approved()))
                .isInstanceOf(BaseException.class));

        wire(config, Map.of("plan_time", "2026-10-20 14:30"));
        when(deviceFacade.resolveDeviceTarget(100L, 33L)).thenReturn(Optional.empty());
        inTx(() -> assertThatThrownBy(() -> recorder.onProcessApproved(approved()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("设备"));
        verify(reservationFacade, never()).createIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }
}
