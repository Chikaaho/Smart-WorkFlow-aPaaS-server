package com.sw.ck.iot.api.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 流程触发结果回写实现：只有真实流程实例创建成功才标记 SUCCESS。
 *
 * <p>返回值区分三种结果（见 {@link IotProcessTriggerFacade} 的两层语义）：改写终态返回
 * {@code Optional.of(true)}；命中终态保护、合法幂等跳过返回 {@code Optional.of(false)}；
 * 无该幂等键的触发记录返回 {@code Optional.empty()}。</p>
 */
@Service
public class IotProcessTriggerFacadeImpl implements IotProcessTriggerFacade {

    private final IotProcessTriggerMapper triggerMapper;

    public IotProcessTriggerFacadeImpl(IotProcessTriggerMapper triggerMapper) {
        this.triggerMapper = triggerMapper;
    }

    @Override
    public Optional<Boolean> markTriggerResult(String idempotentKey, String processInstanceId, String error) {
        IotProcessTrigger trigger = triggerMapper.selectOne(new LambdaQueryWrapper<IotProcessTrigger>()
                .eq(IotProcessTrigger::getIdempotentKey, idempotentKey)
                .last("limit 1"));
        if (trigger == null) {
            return Optional.empty();
        }
        IotProcessTrigger patch = new IotProcessTrigger();
        patch.setId(trigger.getId());
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            // 终态不可被降级：已 SUCCESS 且已有实例标识的行不再改写（迟到/并发写回幂等）
            if ("SUCCESS".equals(trigger.getStatus()) && trigger.getProcessInstanceId() != null
                    && !trigger.getProcessInstanceId().isBlank()) {
                return Optional.of(false);
            }
            patch.setStatus("SUCCESS");
            patch.setProcessInstanceId(processInstanceId);
        } else {
            // 迟到的失败写回不得覆盖已成立的成功发起（否则恢复调度会重投并重复发起流程）
            if ("SUCCESS".equals(trigger.getStatus())) {
                return Optional.of(false);
            }
            patch.setStatus("FAILED");
            patch.setError(error == null ? "流程发起未返回实例" : error);
        }
        patch.setTriggerTime(trigger.getTriggerTime() == null
                ? LocalDateTime.now() : trigger.getTriggerTime());
        triggerMapper.updateById(patch);
        return Optional.of(true);
    }
}
