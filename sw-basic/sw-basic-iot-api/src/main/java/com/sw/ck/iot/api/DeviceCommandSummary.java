package com.sw.ck.iot.api;

/**
 * 设备命令关联摘要（跨模块回查视图；不含实现细节）。
 */
public record DeviceCommandSummary(
        Long commandId,
        String productId,
        String deviceName,
        String commandKey,
        String status,
        String result,
        java.time.LocalDateTime updateTime) {
}
