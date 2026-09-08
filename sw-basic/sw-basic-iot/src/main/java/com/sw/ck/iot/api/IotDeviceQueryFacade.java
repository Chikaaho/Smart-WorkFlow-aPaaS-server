package com.sw.ck.iot.api;

/**
 * IoT 设备查询门面（bpm → iot 只读）。
 */
public interface IotDeviceQueryFacade {

    /**
     * 按主键查询设备业务标识（跨租户由调用方约束）。
     */
    String getDeviceKeyById(Long deviceId);
}
