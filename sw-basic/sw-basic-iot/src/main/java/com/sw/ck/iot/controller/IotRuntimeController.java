package com.sw.ck.iot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.access.prepost.PreAuthorize;
import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotCommand;
import com.sw.ck.iot.entity.IotEventRecord;
import com.sw.ck.iot.entity.IotMessageLog;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.entity.IotPropertyRecord;
import com.sw.ck.iot.entity.IotScriptExec;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRecordMapper;
import com.sw.ck.iot.mapper.IotMessageLogMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import com.sw.ck.iot.mapper.IotScriptExecMapper;
import com.sw.ck.iot.service.IotAuditService;
import com.sw.ck.iot.api.IotDeviceFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

import java.util.List;

/**
 * 运行记录控制器：消息、属性/事件、命令、脚本执行、规则触发与流程发起关联查询。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:runtime:view')")
@RequestMapping("/iot/runtime")
public class IotRuntimeController {

    private final IotMessageLogMapper messageLogMapper;
    private final IotPropertyRecordMapper propertyRecordMapper;
    private final IotEventRecordMapper eventRecordMapper;
    private final IotCommandMapper commandMapper;
    private final IotScriptExecMapper scriptExecMapper;
    private final IotProcessTriggerMapper triggerMapper;
    private final ObjectProvider<IotDeviceFacade> deviceFacadeProvider;
    private final IotDeviceMapper deviceMapper;
    private final IotAuditService auditService;

    public IotRuntimeController(IotMessageLogMapper messageLogMapper,
                                IotPropertyRecordMapper propertyRecordMapper,
                                IotEventRecordMapper eventRecordMapper,
                                IotCommandMapper commandMapper,
                                IotScriptExecMapper scriptExecMapper,
                                IotProcessTriggerMapper triggerMapper,
                                ObjectProvider<IotDeviceFacade> deviceFacadeProvider,
                                IotDeviceMapper deviceMapper,
                                IotAuditService auditService) {
        this.messageLogMapper = messageLogMapper;
        this.propertyRecordMapper = propertyRecordMapper;
        this.eventRecordMapper = eventRecordMapper;
        this.commandMapper = commandMapper;
        this.scriptExecMapper = scriptExecMapper;
        this.triggerMapper = triggerMapper;
        this.deviceFacadeProvider = deviceFacadeProvider;
        this.deviceMapper = deviceMapper;
        this.auditService = auditService;
    }

    /**
     * 命令重试（G5c 审计动作）：按原命令记录重发，生成新命令记录
     * （source_ref=retry:<原命令ID>，沿原 flowInstanceId 关联），失败/超时命令可重试。
     */
    @PostMapping("/commands/{id}/retry")
    public R<com.sw.ck.iot.entity.IotCommand> retryCommand(@PathVariable Long id) {
        IotCommand origin = commandMapper.selectById(id);
        if (origin == null) {
            return R.fail(404, "命令不存在: id=" + id);
        }
        if (origin.getDeviceId() == null) {
            return R.fail(400, "原命令缺少设备，无法重试");
        }
        IotCommand retry = new IotCommand();
        retry.setDeviceId(origin.getDeviceId());
        retry.setConnId(origin.getConnId());
        retry.setProvider(origin.getProvider());
        retry.setCapabilityType(origin.getCapabilityType());
        retry.setCapabilityId(origin.getCapabilityId());
        retry.setParamsJson(origin.getParamsJson());
        retry.setStatus("PENDING");
        retry.setSourceType("RETRY");
        retry.setSourceRef("retry:" + origin.getId());
        retry.setFlowInstanceId(origin.getFlowInstanceId());
        retry.setQos(origin.getQos());
        retry.setCorrelationId(java.util.UUID.randomUUID().toString());
        commandMapper.insert(retry);
        // 经统一命令路径真实重发
        var facade = deviceFacadeProvider.getIfAvailable();
        if (facade != null) {
            com.sw.ck.iot.entity.IotDevice device = deviceMapper.selectById(origin.getDeviceId());
            if (device != null) {
                facade.dispatchByDeviceKey(device.getTenantId(), device.getDeviceKey(),
                        origin.getCapabilityId() == null ? "retry" : origin.getCapabilityId(),
                        origin.getParamsJson() == null ? "{}" : origin.getParamsJson(),
                        origin.getFlowInstanceId());
            }
        }
        auditService.recordAction(origin.getTenantId(), null, "system:iot-command-retry",
                "COMMAND_RETRY", "COMMAND", String.valueOf(retry.getId()), "PENDING",
                retry.getCorrelationId(), "originCommandId=" + origin.getId());
        return R.ok(commandMapper.selectById(retry.getId()));
    }

    @GetMapping("/messages")
    public R<List<IotMessageLog>> messages(@RequestParam(required = false) Long deviceId,
                                           @RequestParam(required = false) String parseStatus) {
        LambdaQueryWrapper<IotMessageLog> wrapper = new LambdaQueryWrapper<>();
        if (deviceId != null) {
            wrapper.eq(IotMessageLog::getDeviceId, deviceId);
        }
        if (parseStatus != null && !parseStatus.isBlank()) {
            wrapper.eq(IotMessageLog::getParseStatus, parseStatus);
        }
        wrapper.orderByDesc(IotMessageLog::getCreateTime).last("limit 200");
        return R.ok(messageLogMapper.selectList(wrapper));
    }

    @GetMapping("/properties")
    public R<List<IotPropertyRecord>> properties(@RequestParam Long deviceId,
                                                 @RequestParam(required = false) String propertyId) {
        LambdaQueryWrapper<IotPropertyRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotPropertyRecord::getDeviceId, deviceId);
        if (propertyId != null && !propertyId.isBlank()) {
            wrapper.eq(IotPropertyRecord::getPropertyId, propertyId);
        }
        wrapper.orderByDesc(IotPropertyRecord::getReportTime).last("limit 200");
        return R.ok(propertyRecordMapper.selectList(wrapper));
    }

    @GetMapping("/events")
    public R<List<IotEventRecord>> events(@RequestParam(required = false) Long deviceId) {
        LambdaQueryWrapper<IotEventRecord> wrapper = new LambdaQueryWrapper<>();
        if (deviceId != null) {
            wrapper.eq(IotEventRecord::getDeviceId, deviceId);
        }
        wrapper.orderByDesc(IotEventRecord::getOccurTime).last("limit 200");
        return R.ok(eventRecordMapper.selectList(wrapper));
    }

    @GetMapping("/commands")
    public R<List<IotCommand>> commands(@RequestParam(required = false) Long deviceId,
                                        @RequestParam(required = false) String sourceType) {
        LambdaQueryWrapper<IotCommand> wrapper = new LambdaQueryWrapper<>();
        if (deviceId != null) {
            wrapper.eq(IotCommand::getDeviceId, deviceId);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            wrapper.eq(IotCommand::getSourceType, sourceType);
        }
        wrapper.orderByDesc(IotCommand::getCreateTime).last("limit 200");
        return R.ok(commandMapper.selectList(wrapper));
    }

    @GetMapping("/script-execs")
    public R<List<IotScriptExec>> scriptExecs(@RequestParam(required = false) Long scriptId) {
        LambdaQueryWrapper<IotScriptExec> wrapper = new LambdaQueryWrapper<>();
        if (scriptId != null) {
            wrapper.eq(IotScriptExec::getScriptId, scriptId);
        }
        wrapper.orderByDesc(IotScriptExec::getCreateTime).last("limit 200");
        return R.ok(scriptExecMapper.selectList(wrapper));
    }

    @GetMapping("/process-triggers")
    public R<List<IotProcessTrigger>> processTriggers(@RequestParam(required = false) Long ruleId) {
        LambdaQueryWrapper<IotProcessTrigger> wrapper = new LambdaQueryWrapper<>();
        if (ruleId != null) {
            wrapper.eq(IotProcessTrigger::getRuleId, ruleId);
        }
        wrapper.orderByDesc(IotProcessTrigger::getCreateTime).last("limit 200");
        return R.ok(triggerMapper.selectList(wrapper));
    }
}
