package com.sw.ck.iot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备管理服务（P21 平台化）：管理状态与连接状态分离、流程接入开关、发布/禁用。
 */
@Service
public class IotDeviceManageService {

    private static final Logger log = LoggerFactory.getLogger(IotDeviceManageService.class);

    private final IotDeviceMapper deviceMapper;
    private final IotProductMapper productMapper;
    private final IotConnectionMapper connectionMapper;
    private final IotTopicMapper topicMapper;
    private final MqttBrokerManager mqttBrokerManager;

    public IotDeviceManageService(IotDeviceMapper deviceMapper,
                                  IotProductMapper productMapper,
                                  IotConnectionMapper connectionMapper,
                                  IotTopicMapper topicMapper,
                                  @Lazy MqttBrokerManager mqttBrokerManager) {
        this.deviceMapper = deviceMapper;
        this.productMapper = productMapper;
        this.connectionMapper = connectionMapper;
        this.topicMapper = topicMapper;
        this.mqttBrokerManager = mqttBrokerManager;
    }

    /**
     * 新增设备（草稿）。
     */
    public IotDevice create(IotDevice device) {
        if (device.getDeviceKey() == null || device.getDeviceKey().isBlank()) {
            throw new IllegalArgumentException("业务标识 deviceKey 不能为空");
        }
        Long count = deviceMapper.selectCount(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceKey, device.getDeviceKey()));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("设备标识已存在: " + device.getDeviceKey());
        }
        if (device.getProductRefId() != null) {
            requireProduct(device.getProductRefId());
        }
        if (device.getConnectionId() != null) {
            requireConnection(device.getConnectionId());
        }
        device.setId(null);
        device.setManageStatus("DRAFT");
        if (device.getProcessAccessEnabled() == null) {
            device.setProcessAccessEnabled(0);
        }
        deviceMapper.insert(device);
        return device;
    }

    /**
     * 编辑（已发布设备的关键身份/产品/物模型变更受控：必须先回草稿或显式确认）。
     */
    public IotDevice update(Long id, IotDevice patch) {
        IotDevice existing = require(id);
        if ("PUBLISHED".equals(existing.getManageStatus())) {
            if (patched(patch, IotDevice::getDeviceKey) || patched(patch, IotDevice::getProductRefId)) {
                throw new IllegalStateException("已发布设备的关键身份变更受控，请先禁用后修改并重新发布");
            }
        }
        patch.setId(id);
        patch.setManageStatus(null);
        patch.setProcessAccessEnabled(null);
        deviceMapper.updateById(patch);
        return deviceMapper.selectById(id);
    }

    /**
     * 发布设备（要求绑定产品且产品物模型已发布）。
     */
    public IotDevice publish(Long id) {
        IotDevice device = require(id);
        IotProduct product = device.getProductRefId() == null
                ? null : productMapper.selectById(device.getProductRefId());
        if (product == null || product.getPublishedModelId() == null) {
            throw new IllegalStateException("设备需绑定已发布物模型的产品后才能发布");
        }
        IotDevice patch = new IotDevice();
        patch.setId(id);
        patch.setManageStatus("PUBLISHED");
        deviceMapper.updateById(patch);
        return deviceMapper.selectById(id);
    }

    /**
     * 禁用/注销。
     */
    public IotDevice changeStatus(Long id, String status) {
        if (!"DISABLED".equals(status) && !"RETIRED".equals(status) && !"DRAFT".equals(status)) {
            throw new IllegalArgumentException("非法管理状态: " + status);
        }
        IotDevice patch = new IotDevice();
        patch.setId(id);
        patch.setManageStatus(status);
        deviceMapper.updateById(patch);
        return deviceMapper.selectById(id);
    }

    /**
     * 流程接入开关（要求设备已发布）。
     */
    public IotDevice changeProcessAccess(Long id, boolean enabled) {
        IotDevice existing = require(id);
        if (enabled && !"PUBLISHED".equals(existing.getManageStatus())) {
            throw new IllegalStateException("未发布设备不能开启流程接入");
        }
        IotDevice patch = new IotDevice();
        patch.setId(id);
        patch.setProcessAccessEnabled(enabled ? 1 : 0);
        deviceMapper.updateById(patch);
        return deviceMapper.selectById(id);
    }

    /**
     * 流程可选设备（服务端校验：已发布 + 开关开启 + 连接启用）。
     */
    public List<IotDevice> listProcessEligible() {
        List<IotDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getManageStatus, "PUBLISHED")
                .eq(IotDevice::getProcessAccessEnabled, 1));
        return devices.stream().filter(d -> {
            if (d.getConnectionId() == null) {
                return true;
            }
            IotConnection conn = connectionMapper.selectById(d.getConnectionId());
            return conn != null && conn.getEnabled() != null && conn.getEnabled() == 1;
        }).toList();
    }

    /**
     * 连接状态刷新（连接状态与管理状态分离）。
     */
    public String refreshConnectionStatus(Long id) {
        IotDevice device = require(id);
        if (device.getConnectionId() == null) {
            return "UNKNOWN";
        }
        boolean online = mqttBrokerManager.isConnected(device.getConnectionId());
        String status = online ? "ONLINE" : "OFFLINE";
        IotDevice patch = new IotDevice();
        patch.setId(id);
        patch.setStatus(status);
        deviceMapper.updateById(patch);
        return status;
    }

    private boolean patched(IotDevice patch, java.util.function.Function<IotDevice, ?> getter) {
        Object value = getter.apply(patch);
        return value != null;
    }

    private IotProduct requireProduct(Long productId) {
        IotProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw new IllegalArgumentException("产品不存在: id=" + productId);
        }
        return product;
    }

    private IotConnection requireConnection(Long connectionId) {
        IotConnection conn = connectionMapper.selectById(connectionId);
        if (conn == null) {
            throw new IllegalArgumentException("连接配置不存在: id=" + connectionId);
        }
        return conn;
    }

    private IotDevice require(Long id) {
        IotDevice device = deviceMapper.selectById(id);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: id=" + id);
        }
        return device;
    }
}
