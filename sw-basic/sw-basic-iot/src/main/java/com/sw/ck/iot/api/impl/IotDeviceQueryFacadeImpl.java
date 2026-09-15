package com.sw.ck.iot.api.impl;

import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.api.IotDeviceQueryFacade;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import org.springframework.stereotype.Service;

/**
 * 设备查询门面实现。
 */
@Service
public class IotDeviceQueryFacadeImpl implements IotDeviceQueryFacade {

    private final IotDeviceMapper deviceMapper;

    public IotDeviceQueryFacadeImpl(IotDeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    @Override
    public String getDeviceKeyById(Long deviceId) {
        IotDevice device = deviceMapper.selectById(deviceId);
        return device == null ? null : device.getDeviceKey();
    }
}
