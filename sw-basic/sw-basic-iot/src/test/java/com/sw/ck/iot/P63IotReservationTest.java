package com.sw.ck.iot;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.iot.api.IotCommandReservationFacade.IotReservationView;
import com.sw.ck.iot.entity.IotCommandReservation;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.job.IotReservationDispatchJob;
import com.sw.ck.iot.mapper.IotCommandReservationMapper;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.IotDeviceService;
import com.sw.ck.iot.service.impl.IotCommandReservationFacadeImpl;
import com.sw.ck.iot.util.DeferredControlUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * P63 一次性预约行为证据：意图幂等（同实例唯一）、创建即过期不补发、
 * 取消与认领竞争单结果、到点认领下发（窗口对齐命令过期 + 立即外发）、过期窗口明确关闭。
 */
class P63IotReservationTest {

    private IotCommandReservationMapper reservationMapper;
    private IotCommandReservationFacade facade;
    private TransactionTemplate txTemplate;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                IotCommandReservation.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                IotDeviceCommand.class);
    }

    @BeforeEach
    void setUp() {
        reservationMapper = mock(IotCommandReservationMapper.class);
        facade = new IotCommandReservationFacadeImpl(reservationMapper, mock(IotDeviceService.class), java.time.Clock.systemUTC());
        // mock insert 回填生成主键（真实链路由数据库/ID 生成器填充）
        when(reservationMapper.insert(any(IotCommandReservation.class))).thenAnswer(inv -> {
            inv.getArgument(0, IotCommandReservation.class).setId(99L);
            return 1;
        });
    }

    private IotReservationIntent intent(LocalDateTime dueUtc) {
        return new IotReservationIntent(100L, "pi-1", "p63_flow", 1, "p63_form", "rec-1",
                "dev-key-1", "prod-1", "dev-1", "power_off", "PROPERTY", "{}",
                dueUtc, "Asia/Shanghai", "2026-10-20 14:30", 60);
    }

    /** 测试本地意图载体（契约 createIntent 已拍平为 JDK 参数）。 */
    private record IotReservationIntent(Long tenantId, String processInstanceId, String processDefKey,
                                        Integer defVersion, String formKey, String recordId,
                                        String deviceKey, String productId, String deviceName,
                                        String commandKey, String commandType, String payloadJson,
                                        LocalDateTime dueAtUtc, String timezoneId,
                                        String dueLocalText, int lateWindowSeconds) {
    }

    private void inTx(Runnable action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("创建意图落 PENDING；同一实例重复成功事件命中既有意图（幂等零新增）")
    void createIntentIdempotentByInstance() {
        when(reservationMapper.selectByInstance(100L, "pi-1")).thenReturn(null, existingRow());
        AtomicReference<Long> firstId = new AtomicReference<>();
        inTx(() -> firstId.set(createIntent(dueInFuture())));
        assertThat(firstId.get()).isNotNull();

        inTx(() -> {
            Long second = createIntent(dueInFuture());
            assertThat(second).as("幂等命中既有意图（返回既有行 id）").isEqualTo(1L);
        });
        verify(reservationMapper, times(1)).insert(any(IotCommandReservation.class));
    }

    @Test
    @DisplayName("预约时刻已在创建时过期（含窗口）→ 保留记录置 EXPIRED，不立即补发")
    void createIntentPastDueExpires() {
        when(reservationMapper.selectByInstance(100L, "pi-1")).thenReturn(null);
        AtomicReference<IotCommandReservation> row = new AtomicReference<>();
        inTx(() -> {
            createIntent(LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2));
            ArgumentCaptor<IotCommandReservation> captor = ArgumentCaptor.forClass(IotCommandReservation.class);
            verify(reservationMapper).insert(captor.capture());
            row.set(captor.getValue());
        });
        assertThat(row.get().getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("取消：仅 PENDING 可取消（条件更新）；已下发返回 NOT_CANCELLABLE；不存在返回 empty")
    void cancelRacesResolveToSingleOutcome() {
        when(reservationMapper.selectById(7L)).thenReturn(pendingRow());
        when(reservationMapper.update(isNull(), any())).thenReturn(1);
        assertThat(facade.cancel(100L, 7L, 9L, "演练取消"))
                .contains(IotCommandReservationFacade.CANCEL_CANCELED);

        when(reservationMapper.update(isNull(), any())).thenReturn(0);
        assertThat(facade.cancel(100L, 7L, 9L, "再次取消"))
                .as("取消与认领竞争：后到者得到明确结果而非成功")
                .contains(IotCommandReservationFacade.CANCEL_NOT_CANCELLABLE);

        when(reservationMapper.selectById(8L)).thenReturn(null);
        assertThat(facade.cancel(100L, 8L, 9L, "x")).isEmpty();
    }

    @Test
    @DisplayName("到点认领下发：入队后命令过期对齐窗口并立即外发；认领后预约置 DISPATCHED+commandId")
    void dispatchJobClaimsDueAndSendsImmediately() {
        IotDeviceCommandMapper commandMapper = mock(IotDeviceCommandMapper.class);
        IotDeviceService deviceService = mock(IotDeviceService.class);
        CommandQueueService queueService = mock(CommandQueueService.class);
        DeferredControlUtil sender = mock(DeferredControlUtil.class);
        IotCommandReservation due = duePendingRow();
        IotCommandReservationMapper mapper = mock(IotCommandReservationMapper.class);
        when(mapper.selectExpiredPending(any())).thenReturn(List.of());
        when(mapper.selectDuePending(any(), any())).thenReturn(List.of(due));
        when(mapper.update(isNull(), any())).thenReturn(1);
        IotDeviceCommand command = new IotDeviceCommand();
        command.setId(555L);
        command.setStatus("QUEUED");
        when(deviceService.dispatchCommandIdempotent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command);
        // P63 G03a 修正后契约：到点一律按 deviceKey+租户权威重解析执行目标
        when(deviceService.resolveDeviceTargetByKey(100L, "dev-key-1"))
                .thenReturn(new com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget("dev-key-1", "P63PROD", "dev-key-1"));
        when(commandMapper.selectById(555L)).thenReturn(command);
        when(commandMapper.update(isNull(), any())).thenReturn(1);

        IotReservationDispatchJob job = new IotReservationDispatchJob(mapper, commandMapper,
                deviceService, provider(sender), queueService, java.time.Clock.systemUTC());
        job.dispatchDueReservations();

        // P63 修复后契约：job 不再预占 SENDING（预占会让共享发送路径 markSending 撞状态判死），
        // 仅做窗口对齐 1 次 update；状态迁移由 sendCommand 内 markSending 统一承载。
        verify(commandMapper, times(1)).update(isNull(), any());
        verify(sender, times(1)).sendCommand(argThat(cmd -> cmd != null && "QUEUED".equals(cmd.getStatus())));
        verify(mapper).update(isNull(), argThat(wrapper -> wrapperSets(
                (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) wrapper, "DISPATCHED")));
        assertThat(due.getCommandId()).isNull(); // patch 经 wrapper 落库，实体断言以 wrapper 为准
    }

    @Test
    @DisplayName("设备失效（按 key 解析为空）→ FAILED + 拒绝原因可查；不静默跳过")
    void dispatchJobMarksFailedWhenDeviceInvalid() {
        IotDeviceCommandMapper commandMapper = mock(IotDeviceCommandMapper.class);
        IotDeviceService deviceService = mock(IotDeviceService.class);
        CommandQueueService queueService = mock(CommandQueueService.class);
        IotCommandReservation due = duePendingRow();
        due.setProductId(null); // 需要 key 解析
        when(deviceService.resolveDeviceTargetByKey(100L, "dev-key-1")).thenReturn(null);
        IotCommandReservationMapper mapper = mock(IotCommandReservationMapper.class);
        when(mapper.selectExpiredPending(any())).thenReturn(List.of());
        when(mapper.selectDuePending(any(), any())).thenReturn(List.of(due));
        when(mapper.update(isNull(), any())).thenReturn(1);

        IotReservationDispatchJob job = new IotReservationDispatchJob(mapper, commandMapper,
                deviceService, provider(null), queueService, java.time.Clock.systemUTC());
        job.dispatchDueReservations();

        verify(mapper).update(isNull(), argThat(wrapper -> wrapperSets(
                (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) wrapper, "FAILED")));
        verify(deviceService, never()).dispatchCommandIdempotent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("超过迟到窗口的 PENDING → EXPIRED（不外发）")
    void dispatchJobExpiresOverdue() {
        IotDeviceCommandMapper commandMapper = mock(IotDeviceCommandMapper.class);
        IotDeviceService deviceService = mock(IotDeviceService.class);
        CommandQueueService queueService = mock(CommandQueueService.class);
        IotCommandReservation overdue = duePendingRow();
        overdue.setDueAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(3));
        IotCommandReservationMapper mapper = mock(IotCommandReservationMapper.class);
        when(mapper.selectExpiredPending(any())).thenReturn(List.of(overdue));
        when(mapper.selectDuePending(any(), any())).thenReturn(List.of());
        when(mapper.update(isNull(), any())).thenReturn(1);

        IotReservationDispatchJob job = new IotReservationDispatchJob(mapper, commandMapper,
                deviceService, provider(null), queueService, java.time.Clock.systemUTC());
        job.dispatchDueReservations();

        verify(mapper).update(isNull(), argThat(wrapper -> wrapperSets(
                (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) wrapper, "EXPIRED")));
        verify(deviceService, never()).dispatchCommandIdempotent(any(), any(), any(), any(), any(), any(), any());
    }

    private IotCommandReservation existingRow() {
        IotCommandReservation row = new IotCommandReservation();
        row.setId(1L);
        row.setTenantId(100L);
        row.setProcessInstanceId("pi-1");
        row.setStatus("PENDING");
        return row;
    }

    private IotCommandReservation pendingRow() {
        return existingRow();
    }

    private IotCommandReservation duePendingRow() {
        IotCommandReservation row = existingRow();
        row.setId(9L);
        row.setDeviceKey("dev-key-1");
        row.setProductId("prod-1");
        row.setDeviceName("dev-1");
        row.setCommandKey("power_off");
        row.setCommandType("PROPERTY");
        row.setPayload("{}");
        row.setDueAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(5));
        row.setLateWindowSeconds(60);
        return row;
    }

    private Long createIntent(LocalDateTime dueUtc) {
        IotReservationIntent i = intent(dueUtc);
        return facade.createIntent(i.tenantId(), i.processInstanceId(), i.processDefKey(), i.defVersion(),
                i.formKey(), i.recordId(), i.deviceKey(), i.productId(), i.deviceName(),
                i.commandKey(), i.commandType(), i.payloadJson(), i.dueAtUtc(), i.timezoneId(),
                i.dueLocalText(), i.lateWindowSeconds()).orElseThrow();
    }

    private LocalDateTime dueInFuture() {
        return LocalDateTime.now().plusHours(2);
    }

    /** LambdaUpdateWrapper 的 set 值检查（toString 不含字面值，检查参数值表）。 */
    private boolean wrapperSets(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> wrapper,
                                String needle) {
        return wrapper.getParamNameValuePairs().values().stream()
                .anyMatch(value -> String.valueOf(value).contains(needle));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
        };
    }
}
