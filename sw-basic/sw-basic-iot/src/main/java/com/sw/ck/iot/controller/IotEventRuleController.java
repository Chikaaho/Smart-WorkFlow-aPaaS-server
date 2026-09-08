package com.sw.ck.iot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotEventRule;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.api.IotFormContractChecker;
import com.sw.ck.iot.mapper.IotEventRuleMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.iot.service.IotAuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/**
 * 事件规则控制器：条件/阈值/防抖/冷却/流程模板/表单映射与发布。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:rule:manage')")
@RequestMapping("/iot/rules")
public class IotEventRuleController {

    private final IotEventRuleMapper ruleMapper;
    private final IotProcessTriggerMapper triggerMapper;
    private final ObjectProvider<IotFormContractChecker> formContractCheckerProvider;
    private final IotAuditService auditService;

    public IotEventRuleController(IotEventRuleMapper ruleMapper,
                                  IotProcessTriggerMapper triggerMapper,
                                  ObjectProvider<IotFormContractChecker> formContractCheckerProvider,
                                  IotAuditService auditService) {
        this.ruleMapper = ruleMapper;
        this.triggerMapper = triggerMapper;
        this.formContractCheckerProvider = formContractCheckerProvider;
        this.auditService = auditService;
    }

    @GetMapping
    public R<List<IotEventRule>> list(@RequestParam(required = false) Long deviceId) {
        LambdaQueryWrapper<IotEventRule> wrapper = new LambdaQueryWrapper<>();
        if (deviceId != null) {
            wrapper.eq(IotEventRule::getDeviceId, deviceId);
        }
        return R.ok(ruleMapper.selectList(wrapper));
    }

    @GetMapping("/{id}")
    public R<IotEventRule> detail(@PathVariable Long id) {
        return R.ok(ruleMapper.selectById(id));
    }

    @PostMapping
    public R<IotEventRule> create(@RequestBody IotEventRule rule) {
        validate(rule);
        rule.setId(null);
        rule.setStatus("DRAFT");
        rule.setRuleVersion(1);
        if (rule.getProcessEnabled() == null) {
            rule.setProcessEnabled(0);
        }
        ruleMapper.insert(rule);
        return R.ok(rule);
    }

    @PutMapping("/{id}")
    public R<IotEventRule> update(@PathVariable Long id, @RequestBody IotEventRule patch) {
        IotEventRule existing = ruleMapper.selectById(id);
        if (existing == null) {
            return R.fail(404, "规则不存在: id=" + id);
        }
        validate(patch);
        patch.setId(id);
        patch.setStatus(null);
        patch.setRuleVersion(null);
        ruleMapper.updateById(patch);
        return R.ok(ruleMapper.selectById(id));
    }

    /**
     * 发布前验证设备/脚本/流程模板/表单映射可用性的基础校验在 validate()；
     * 发布动作锁定版本。
     */
    @PostMapping("/{id}/publish")
    public R<IotEventRule> publish(@PathVariable Long id) {
        IotEventRule existing = ruleMapper.selectById(id);
        if (existing == null) {
            return R.fail(404, "规则不存在: id=" + id);
        }
        // G3 表单契约校验：发布时按真实流程模板与表单版本校验字段映射
        if (existing.getProcessEnabled() != null && existing.getProcessEnabled() == 1
                && formContractCheckerProvider.getIfAvailable() instanceof IotFormContractChecker checker) {
            List<String> errors = checker.checkMapping(existing.getProcessTemplateKey(),
                    existing.getFormMappingJson());
            if (!errors.isEmpty()) {
                return R.fail(400, "表单契约校验失败: " + String.join("; ", errors));
            }
        }
        IotEventRule patch = new IotEventRule();
        patch.setId(id);
        patch.setStatus("PUBLISHED");
        ruleMapper.updateById(patch);
        auditService.recordAction(existing.getTenantId(), null, "system:iot-rule-publish",
                "RULE_PUBLISH", "RULE", String.valueOf(id), "PUBLISHED",
                java.util.UUID.randomUUID().toString(), "ruleVersion=" + existing.getRuleVersion());
        return R.ok(ruleMapper.selectById(id));
    }

    @PostMapping("/{id}/disable")
    public R<IotEventRule> disable(@PathVariable Long id) {
        IotEventRule patch = new IotEventRule();
        patch.setId(id);
        patch.setStatus("DISABLED");
        ruleMapper.updateById(patch);
        return R.ok(ruleMapper.selectById(id));
    }

    /**
     * 规则触发记录（含流程发起结果）。
     */
    @GetMapping("/{id}/triggers")
    public R<List<IotProcessTrigger>> triggers(@PathVariable Long id) {
        return R.ok(triggerMapper.selectList(new LambdaQueryWrapper<IotProcessTrigger>()
                .eq(IotProcessTrigger::getRuleId, id)
                .orderByDesc(IotProcessTrigger::getCreateTime)
                .last("limit 100")));
    }

    private void validate(IotEventRule rule) {
        if (rule.getDeviceId() == null) {
            throw new IllegalArgumentException("规则必须绑定设备");
        }
        if (!"PROPERTY_CHANGED".equals(rule.getRuleType())
                && !"THRESHOLD".equals(rule.getRuleType())
                && !"EVENT_OCCUR".equals(rule.getRuleType())
                && !"ONLINE".equals(rule.getRuleType())
                && !"OFFLINE".equals(rule.getRuleType())) {
            throw new IllegalArgumentException("非法规则类型: " + rule.getRuleType());
        }
        if (rule.getConditionJson() == null || rule.getConditionJson().isBlank()) {
            throw new IllegalArgumentException("规则条件不能为空");
        }
        if (rule.getProcessEnabled() != null && rule.getProcessEnabled() == 1
                && (rule.getProcessTemplateKey() == null || rule.getProcessTemplateKey().isBlank())) {
            throw new IllegalArgumentException("启用流程联动必须指定流程模板 key");
        }
    }
}
