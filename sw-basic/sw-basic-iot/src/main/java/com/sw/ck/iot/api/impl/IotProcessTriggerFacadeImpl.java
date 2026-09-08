package com.sw.ck.iot.api.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 流程触发结果回写实现：只有真实流程实例创建成功才标记 SUCCESS。
 */
@Service
public class IotProcessTriggerFacadeImpl implements IotProcessTriggerFacade {

    private final IotProcessTriggerMapper triggerMapper;

    public IotProcessTriggerFacadeImpl(IotProcessTriggerMapper triggerMapper) {
        this.triggerMapper = triggerMapper;
    }

    @Override
    public void markTriggerResult(String idempotentKey, String processInstanceId, String error) {
        IotProcessTrigger trigger = triggerMapper.selectOne(new LambdaQueryWrapper<IotProcessTrigger>()
                .eq(IotProcessTrigger::getIdempotentKey, idempotentKey)
                .last("limit 1"));
        if (trigger == null) {
            return;
        }
        IotProcessTrigger patch = new IotProcessTrigger();
        patch.setId(trigger.getId());
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            patch.setStatus("SUCCESS");
            patch.setProcessInstanceId(processInstanceId);
        } else {
            patch.setStatus("FAILED");
            patch.setError(error == null ? "流程发起未返回实例" : error);
        }
        patch.setTriggerTime(trigger.getTriggerTime() == null
                ? LocalDateTime.now() : trigger.getTriggerTime());
        triggerMapper.updateById(patch);
    }
}
