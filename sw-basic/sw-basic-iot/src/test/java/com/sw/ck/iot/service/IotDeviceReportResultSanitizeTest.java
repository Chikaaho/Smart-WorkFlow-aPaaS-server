package com.sw.ck.iot.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.service.impl.IotDeviceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R8c：设备可控回写文本的净化边界。
 * <p>设备回写的 result 是完全可控输入（可携带堆栈帧、绝对路径、超长噪声），
 * 必须经 DiagnosticText 清洗限长后落库：授权运维仍可按分类摘要定位，
 * 但原始噪声不进存储与页面。</p>
 */
@ExtendWith(MockitoExtension.class)
class IotDeviceReportResultSanitizeTest {

    @Mock
    private IotDeviceCommandMapper commandMapper;

    private IotDeviceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IotDeviceServiceImpl(commandMapper);
    }

    private IotDeviceCommand existing() {
        IotDeviceCommand command = new IotDeviceCommand();
        command.setId(7L);
        command.setProductId("prod-1");
        command.setDeviceName("dev-1");
        command.setStatus("PENDING");
        return command;
    }

    @Test
    @DisplayName("FAILED 回写携带堆栈帧与超长噪声 → 清洗限长后落库，保留可定位摘要")
    void reportResult_sanitizesDeviceControlledText() {
        when(commandMapper.selectById(7L)).thenReturn(existing());
        when(commandMapper.updateById(any(IotDeviceCommand.class))).thenReturn(1);

        String stackLike = "java.lang.RuntimeException: P61-R8C-MARKER connect refused"
                + "\n\tat com.vendor.device.Internal.build(Internal.java:88)"
                + "\n\tat com.vendor.device.Internal.main(Internal.java:1)";
        String noisy = stackLike + " /absolute/etc/path ".repeat(200);

        service.reportResult(7L, "FAILED", noisy);

        ArgumentCaptor<IotDeviceCommand> captor = ArgumentCaptor.forClass(IotDeviceCommand.class);
        org.mockito.Mockito.verify(commandMapper).updateById(captor.capture());
        String stored = captor.getValue().getResult();

        // 限长生效：远小于注入的噪声长度
        assertTrue(stored.length() <= 2000, "存储文本必须限长，实际 " + stored.length());
        // 清洗生效：堆栈帧前缀被剥除、绝对路径被掩码，不再原样入库
        assertFalse(stored.contains("\n\tat "), "堆栈帧不得原样入库");
        assertFalse(stored.contains("/absolute/etc/path /absolute/etc/path"),
                "重复路径噪声必须被截断");
        // 授权运维可定位：分类摘要保留标识
        assertTrue(stored.contains("P61-R8C-MARKER"), "可定位标识应保留");
    }

    @Test
    @DisplayName("非法 status → 拒绝且不落库")
    void reportResult_rejectsIllegalStatus() {
        when(commandMapper.selectById(7L)).thenReturn(existing());
        assertThrows(com.sw.ck.common.exception.BaseException.class,
                () -> service.reportResult(7L, "WHATEVER", "x"));
        org.mockito.Mockito.verify(commandMapper, org.mockito.Mockito.never())
                .updateById(any(IotDeviceCommand.class));
    }
}
