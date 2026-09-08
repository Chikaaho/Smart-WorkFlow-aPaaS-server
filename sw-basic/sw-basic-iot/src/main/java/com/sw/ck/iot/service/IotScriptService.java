package com.sw.ck.iot.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotScript;
import com.sw.ck.iot.entity.IotScriptExec;
import com.sw.ck.iot.entity.IotScriptVersion;
import com.sw.ck.iot.mapper.IotScriptMapper;
import com.sw.ck.iot.mapper.IotScriptVersionMapper;
import com.sw.ck.iot.script.ScriptEngineService;
import com.sw.ck.iot.script.ScriptRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 受控脚本生命周期服务：草稿、语法/安全校验、试运行、发布、版本留痕、停用。
 * <p>
 * 已发布规则和流程固定脚本版本；修改草稿不影响历史执行。
 * MESSAGE 触发类型的脚本在消息入口处被回调（副作用开关由触发路径决定）。
 * </p>
 */
@Service
public class IotScriptService {

    private static final Logger log = LoggerFactory.getLogger(IotScriptService.class);

    private final IotScriptMapper scriptMapper;
    private final IotScriptVersionMapper versionMapper;
    private final ScriptEngineService engineService;
    private IotAuditService auditService;

    public IotScriptService(IotScriptMapper scriptMapper,
                            IotScriptVersionMapper versionMapper,
                            @Lazy ScriptEngineService engineService) {
        this.scriptMapper = scriptMapper;
        this.versionMapper = versionMapper;
        this.engineService = engineService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setAuditService(IotAuditService auditService) {
        this.auditService = auditService;
    }

    public List<IotScript> list() {
        return scriptMapper.selectList(new LambdaQueryWrapper<>());
    }

    public IotScript getById(Long id) {
        return scriptMapper.selectById(id);
    }

    public IotScriptVersion getVersion(Long scriptId, Integer version) {
        return versionMapper.selectOne(new LambdaQueryWrapper<IotScriptVersion>()
                .eq(IotScriptVersion::getScriptId, scriptId)
                .eq(IotScriptVersion::getScriptVersion, version)
                .last("limit 1"));
    }

    public List<IotScriptVersion> versions(Long scriptId) {
        return versionMapper.selectList(new LambdaQueryWrapper<IotScriptVersion>()
                .eq(IotScriptVersion::getScriptId, scriptId)
                .orderByDesc(IotScriptVersion::getScriptVersion));
    }

    /**
     * 新建脚本（草稿 v1）。
     */
    public IotScript create(IotScript script, String sourceCode) {
        Long count = scriptMapper.selectCount(new LambdaQueryWrapper<IotScript>()
                .eq(IotScript::getCode, script.getCode()));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("脚本编码已存在: " + script.getCode());
        }
        if (!"JS".equalsIgnoreCase(script.getLanguage()) && !"JAVA".equalsIgnoreCase(script.getLanguage())) {
            throw new IllegalArgumentException("语言仅支持 JS / JAVA: " + script.getLanguage());
        }
        script.setId(null);
        script.setCurrentVersion(1);
        script.setPublishedVersion(null);
        script.setStatus("DRAFT");
        scriptMapper.insert(script);
        saveDraftVersion(script, 1, sourceCode);
        return script;
    }

    /**
     * 更新草稿（产生新草稿版本号；已发布版本不受影响）。
     */
    public IotScript updateDraft(Long id, IotScript patch, String sourceCode) {
        IotScript existing = require(id);
        if ("DISABLED".equals(existing.getStatus())) {
            throw new IllegalStateException("脚本已停用，不能修改");
        }
        patch.setId(id);
        patch.setLanguage(null);
        int nextVersion = existing.getCurrentVersion() == null ? 1 : existing.getCurrentVersion() + 1;
        patch.setCurrentVersion(nextVersion);
        scriptMapper.updateById(patch);
        if (sourceCode != null) {
            saveDraftVersion(existing, nextVersion, sourceCode);
        }
        return scriptMapper.selectById(id);
    }

    /**
     * 语法/安全校验当前草稿。
     */
    public ScriptRunResult validate(Long id) {
        IotScript script = require(id);
        IotScriptVersion draft = getVersion(id, script.getCurrentVersion());
        if (draft == null) {
            return ScriptRunResult.failure("草稿版本不存在", 0, List.of());
        }
        return engineService.validate(script, draft.getSourceCode());
    }

    /**
     * 试运行（默认不产生真实副作用）。
     */
    public IotScriptExec dryRun(Long id, Long tenantId, Long actorId, Map<String, Object> input) {
        IotScript script = require(id);
        IotScriptVersion version = getVersion(id, script.getCurrentVersion());
        if (version == null) {
            throw new IllegalArgumentException("草稿版本不存在");
        }
        return engineService.dryRun(script, version, tenantId, actorId, input);
    }

    /**
     * 发布当前草稿版本（发布前强制校验）。
     */
    public IotScript publish(Long id) {
        IotScript script = require(id);
        ScriptRunResult check = validate(id);
        if (!"SUCCESS".equals(check.getStatus())) {
            throw new IllegalStateException("发布前校验失败: " + check.getError());
        }
        IotScript patch = new IotScript();
        patch.setId(id);
        patch.setStatus("PUBLISHED");
        patch.setPublishedVersion(script.getCurrentVersion());
        scriptMapper.updateById(patch);
        IotScriptVersion version = getVersion(id, script.getCurrentVersion());
        IotScriptVersion versionPatch = new IotScriptVersion();
        versionPatch.setId(version.getId());
        versionPatch.setStatus("PUBLISHED");
        versionPatch.setPublishTime(LocalDateTime.now());
        versionMapper.updateById(versionPatch);
        if (auditService != null) {
            auditService.recordAction(script.getTenantId(), null, "system:iot-script-publish",
                    "SCRIPT_PUBLISH", "SCRIPT", String.valueOf(id), "PUBLISHED",
                    java.util.UUID.randomUUID().toString(),
                    "scriptVersion=" + script.getCurrentVersion());
        }
        return scriptMapper.selectById(id);
    }

    /**
     * 停用脚本。
     */
    public void disable(Long id) {
        IotScript patch = new IotScript();
        patch.setId(id);
        patch.setStatus("DISABLED");
        scriptMapper.updateById(patch);
    }

    /**
     * MESSAGE 触发回调（消息入口调用；真实路径副作用开启）。
     */
    public void onMessageTriggered(IotDevice device, String topic, String payload, String dedupKey) {
        if (device == null) {
            return;
        }
        List<IotScript> scripts = scriptMapper.selectList(new LambdaQueryWrapper<IotScript>()
                .eq(IotScript::getTriggerType, "MESSAGE")
                .eq(IotScript::getStatus, "PUBLISHED"));
        for (IotScript script : scripts) {
            if (!matchesBinding(script, device, topic)) {
                continue;
            }
            IotScriptVersion version = getVersion(script.getId(), script.getPublishedVersion());
            if (version == null) {
                continue;
            }
            Map<String, Object> input = new HashMap<>();
            input.put("topic", topic);
            input.put("payload", payload);
            input.put("deviceKey", device.getDeviceKey());
            // Long 精度：JS Number 安全整数 2^53，deviceId 以字符串传递
            input.put("deviceId", String.valueOf(device.getId()));
            try {
                engineService.realRun(script, version, device.getTenantId(), null,
                        "MESSAGE", topic, device.getId(), input, dedupKey);
            } catch (Exception e) {
                // 单脚本失败不拖垮消息消费
                log.error("MESSAGE 脚本执行异常: scriptId={}, error={}", script.getId(), e.getMessage(), e);
            }
        }
    }

    private boolean matchesBinding(IotScript script, IotDevice device, String topic) {
        if (script.getBindingJson() == null || script.getBindingJson().isBlank()) {
            return true;
        }
        Map<String, Object> binding = JSON.parseObject(script.getBindingJson(), Map.class);
        Object boundTopic = binding.get("topic");
        if (boundTopic != null && !topic.equals(String.valueOf(boundTopic))) {
            return false;
        }
        Object boundDeviceKey = binding.get("deviceKey");
        if (boundDeviceKey != null && !String.valueOf(boundDeviceKey).equals(device.getDeviceKey())) {
            return false;
        }
        Object boundProductId = binding.get("productId");
        if (boundProductId != null && device.getProductRefId() != null
                && !String.valueOf(boundProductId).equals(String.valueOf(device.getProductRefId()))) {
            return false;
        }
        return true;
    }

    private void saveDraftVersion(IotScript script, int version, String sourceCode) {
        IotScriptVersion existing = getVersion(script.getId(), version);
        IotScriptVersion record = existing == null ? new IotScriptVersion() : existing;
        record.setScriptId(script.getId());
        record.setScriptVersion(version);
        record.setSourceCode(sourceCode);
        record.setStatus("DRAFT");
        if (existing == null) {
            versionMapper.insert(record);
        } else {
            versionMapper.updateById(record);
        }
    }

    private IotScript require(Long id) {
        IotScript script = scriptMapper.selectById(id);
        if (script == null) {
            throw new IllegalArgumentException("脚本不存在: id=" + id);
        }
        return script;
    }

    /**
     * 执行记录查询（运行记录页）。
     */
    public List<IotScriptExec> listExecs(Long scriptId) {
        return engineService.recentExecs(scriptId, 100);
    }

}
