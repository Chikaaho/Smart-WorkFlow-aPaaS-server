package com.sw.ck.iot.api.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.entity.IotCommand;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import com.sw.ck.iot.service.IotAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A6 统一命令下发（MQTT 路径）：运行时重校验 + 单命令 + 全链同关联标识。
 * <p>
 * 命令记录（sw_iot_command）先行，Broker 接收（BROKER_ACK）与设备执行
 * （设备经 ACTION_RESULT 回报 SUCCESS/FAILED）分状态记录。
 * </p>
 */
@Service
public class IotDeviceMqttDispatchService {

    private static final Logger log = LoggerFactory.getLogger(IotDeviceMqttDispatchService.class);

    private final IotDeviceMapper deviceMapper;
    private final IotConnectionMapper connectionMapper;
    private final IotTopicMapper topicMapper;
    private final IotCommandMapper commandMapper;
    private final MqttBrokerManager mqttBrokerManager;
    private final IotAuditService auditService;

    public IotDeviceMqttDispatchService(IotDeviceMapper deviceMapper,
                                        IotConnectionMapper connectionMapper,
                                        IotTopicMapper topicMapper,
                                        IotCommandMapper commandMapper,
                                        @Lazy MqttBrokerManager mqttBrokerManager,
                                        IotAuditService auditService) {
        this.deviceMapper = deviceMapper;
        this.connectionMapper = connectionMapper;
        this.topicMapper = topicMapper;
        this.commandMapper = commandMapper;
        this.mqttBrokerManager = mqttBrokerManager;
        this.auditService = auditService;
    }

    /**
     * @return 命令记录 ID；设备/连接不合格返回 null（不伪造受理成功）
     */
    public Long dispatch(Long tenantId, String deviceKey, String commandKey,
                         String payload, String approvalBizId) {
        return dispatch(tenantId, deviceKey, commandKey, payload, approvalBizId, null);
    }

    /**
     * @param sourceTag A6 来源标识（FIXED/FORM_FIELD/VARIABLE），写入 sourceRef 供审计区分
     */
    public Long dispatch(Long tenantId, String deviceKey, String commandKey,
                         String payload, String approvalBizId, String sourceTag) {
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceKey, deviceKey)
                .last("limit 1"));
        // 运行时重校验：不能仅信任流程里已保存的配置
        if (device == null || !device.getTenantId().equals(tenantId)
                || !"PUBLISHED".equals(device.getManageStatus())
                || device.getProcessAccessEnabled() == null || device.getProcessAccessEnabled() != 1) {
            log.warn("A6 运行时校验拒绝: deviceKey={}, 存在={}, 状态={}", deviceKey, device != null,
                    device == null ? null : device.getManageStatus());
            return null;
        }
        if (device.getConnectionId() == null) {
            return null;
        }
        IotConnection conn = connectionMapper.selectById(device.getConnectionId());
        if (conn == null || conn.getEnabled() == null || conn.getEnabled() != 1) {
            return null;
        }
        // 下行主题：产品绑定的 DOWN/BOTH Topic（取第一条）
        IotTopic downTopic = topicMapper.selectOne(new LambdaQueryWrapper<IotTopic>()
                .eq(IotTopic::getConnId, conn.getId())
                .in(IotTopic::getDirection, "DOWN", "BOTH")
                .eq(IotTopic::getEnabled, 1)
                .last("limit 1"));
        if (downTopic == null) {
            log.warn("A6 无可用下行主题: deviceKey={}", deviceKey);
            return null;
        }
        String topic = downTopic.getTopic().contains("{deviceKey}")
                ? downTopic.getTopic().replace("{deviceKey}", deviceKey)
                : downTopic.getTopic();
        IotCommand command = new IotCommand();
        command.setDeviceId(device.getId());
        command.setConnId(conn.getId());
        command.setProvider("MQTT");
        command.setCapabilityType("INVOKE_ACTION");
        command.setCapabilityId(commandKey);
        command.setParamsJson(payload);
        command.setStatus("PENDING");
        command.setSourceType("FLOW");
        command.setSourceRef("flow:" + approvalBizId + (sourceTag == null ? "" : ":" + sourceTag));
        command.setFlowInstanceId(approvalBizId);
        command.setQos(downTopic.getQos() == null ? 1 : downTopic.getQos());
        command.setCorrelationId(UUID.randomUUID().toString());
        commandMapper.insert(command);
        try {
            var result = mqttBrokerManager.publish(conn.getId(), topic, payload,
                    command.getQos(), false);
            IotCommand patch = new IotCommand();
            patch.setId(command.getId());
            patch.setStatus(Boolean.TRUE.equals(result.get("brokerAck")) ? "BROKER_ACK" : "PENDING");
            patch.setSentTime(LocalDateTime.now());
            commandMapper.updateById(patch);
            auditService.recordAction(tenantId, null, "system:iot-command-dispatch",
                    "COMMAND_DISPATCH", "COMMAND", String.valueOf(command.getId()),
                    patch.getStatus(), command.getCorrelationId(),
                    "deviceId=" + device.getId() + ", source=" + command.getSourceRef());
            log.info("A6 命令已下发: commandId={}, topic={}, flowInstanceId={}",
                    command.getId(), topic, approvalBizId);
        } catch (Exception e) {
            IotCommand patch = new IotCommand();
            patch.setId(command.getId());
            patch.setStatus("FAILED");
            patch.setError(trim(e.getMessage(), 500));
            commandMapper.updateById(patch);
            auditService.recordAction(tenantId, null, "system:iot-command-dispatch",
                    "COMMAND_DISPATCH", "COMMAND", String.valueOf(command.getId()),
                    "FAILED", command.getCorrelationId(), "dispatch failed");
        }
        return command.getId();
    }

    private String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
