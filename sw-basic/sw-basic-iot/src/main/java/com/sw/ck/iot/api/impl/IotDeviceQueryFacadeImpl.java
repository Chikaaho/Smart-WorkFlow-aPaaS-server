package com.sw.ck.iot.api.impl;

import com.sw.ck.iot.api.IotDeviceQueryFacade;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 设备查询门面实现：设备不存在映射为 {@code Optional.empty()}（查询目标缺失），
 * 不返回 null，由调用方显式处理。
 */
@Service
public class IotDeviceQueryFacadeImpl implements IotDeviceQueryFacade {

    private final IotDeviceMapper deviceMapper;

    public IotDeviceQueryFacadeImpl(IotDeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    @Override
    public Optional<String> getDeviceKeyById(Long deviceId) {
        IotDevice device = deviceMapper.selectById(deviceId);
        return device == null ? Optional.empty() : Optional.ofNullable(device.getDeviceKey());
    }
}
