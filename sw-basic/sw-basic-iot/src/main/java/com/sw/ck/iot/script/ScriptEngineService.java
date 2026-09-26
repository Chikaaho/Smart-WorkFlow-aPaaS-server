package com.sw.ck.iot.script;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotScript;
import com.sw.ck.iot.entity.IotScriptExec;
import com.sw.ck.iot.entity.IotScriptVersion;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRecordMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import com.sw.ck.iot.mapper.IotScriptExecMapper;
import com.sw.ck.iot.mapper.IotScriptMapper;
import com.sw.ck.iot.mapper.IotScriptVersionMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 受控脚本执行编排。
 * <p>
 * 负责构建执行上下文（租户/操作者/版本/触发来源/副作用开关）、
 * 按 JS/Java 分发到对应隔离执行器，并把执行输入、输出、日志、耗时、状态
 * 落入可查询运行记录。
 * </p>
 */
@Service
public class ScriptEngineService {

    private final GraalJsRunner jsRunner;
    private final JavaSubprocessExecutor javaExecutor;
    private final ScriptBeanAccess beans;

    /** 幂等插入的保存点包装所需（可选装配：单元测试可缺省）。 */
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionManager(
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public ScriptEngineService(GraalJsRunner jsRunner,
                               JavaSubprocessExecutor javaExecutor,
                               ScriptBeanAccess beans) {
        this.jsRunner = jsRunner;
        this.javaExecutor = javaExecutor;
        this.beans = beans;
    }

    /**
     * 试运行（副作用默认关闭）。
     */
    public IotScriptExec dryRun(IotScript script, IotScriptVersion version,
                                Long tenantId, Long actorId, Map<String, Object> input) {
        return execute(script, version, tenantId, actorId, "MANUAL", "dryrun", null,
                input, false, null);
    }

    /**
     * 真实执行（副作用开启，须权限调用方保证）。
     */
    public IotScriptExec realRun(IotScript script, IotScriptVersion version,
                                 Long tenantId, Long actorId, String triggerSource,
                                 String triggerRef, Long deviceId,
                                 Map<String, Object> input, String idempotentKey) {
        return execute(script, version, tenantId, actorId, triggerSource, triggerRef, deviceId,
                input, true, idempotentKey);
    }

    /**
     * 语法/安全校验。
     */
    public ScriptRunResult validate(IotScript script, String sourceCode) {
        if ("JAVA".equalsIgnoreCase(script.getLanguage())) {
            return javaExecutor.validate(sourceCode);
        }
        return jsRunner.validate(sourceCode);
    }

    private IotScriptExec execute(IotScript script, IotScriptVersion version,
                                  Long tenantId, Long actorId, String triggerSource,
                                  String triggerRef, Long deviceId,
                                  Map<String, Object> input, boolean sideEffect, String idempotentKey) {
        ScriptExecutionSpec spec = new ScriptExecutionSpec();
        spec.setScriptId(script.getId());
        spec.setScriptVersion(version.getScriptVersion());
        spec.setLanguage(script.getLanguage());
        spec.setSourceCode(version.getSourceCode());
        spec.setTenantId(tenantId);
        spec.setActorId(actorId);
        spec.setTriggerSource(triggerSource);
        spec.setTriggerRef(triggerRef);
        spec.setCorrelationId(idempotentKey == null || idempotentKey.isBlank()
                ? UUID.randomUUID().toString() : idempotentKey);
        spec.setDeviceId(deviceId);
        spec.setSideEffectAllowed(sideEffect);
        spec.setInput(input);
        spec.setTimeoutMs(script.getTimeoutMs() == null ? 5000 : script.getTimeoutMs());

        ScriptHostFunctions host = new ScriptHostFunctions(
                beans.deviceMapper, beans.connectionMapper, beans.topicMapper, beans.commandMapper,
                beans.propertyRecordMapper, beans.eventRecordMapper, beans.productMapper,
                beans.thingModelMapper, beans.mqttBrokerManager, beans.eventPublisher, spec);

        ScriptRunResult result;
        if ("JAVA".equalsIgnoreCase(script.getLanguage())) {
            result = javaExecutor.run(spec, host);
        } else {
            result = jsRunner.run(spec, host);
        }

        IotScriptExec exec = new IotScriptExec();
        exec.setScriptId(script.getId());
        exec.setScriptVersion(version.getScriptVersion());
        exec.setTriggerSource(triggerSource);
        exec.setTriggerRef(triggerRef);
        exec.setDeviceRef(deviceId == null ? null : String.valueOf(deviceId));
        exec.setStatus(result.getStatus());
        exec.setInputJson(input == null ? null : JSON.toJSONString(input));
        exec.setOutputJson(truncate(result.getOutputJson(), 2000));
        exec.setError(sanitizeDiagnostic(result.getError(), 1000));
        exec.setDurationMs(result.getDurationMs());
        exec.setSideEffect(sideEffect ? 1 : 0);
        exec.setIdempotentKey(idempotentKey);
        exec.setCorrelationId(spec.getCorrelationId());
        beans.scriptExecMapper.insert(exec);
        persistProcessStartIntents(host, sideEffect);
        return exec;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * 脱敏后的诊断摘要（P61 §3.2）。
     * <p>
     * 脚本引擎（GraalVM / javac / 子进程）的失败文本会经执行记录对外可见，且带出
     * 编译临时文件的绝对路径等环境细节。此处先剥掉绝对路径再截断：保留脚本作者
     * 定位所需的语义，同时不暴露服务端文件系统布局。完整原文仍在服务端结构化日志中可查。
     * </p>
     */
    private String sanitizeDiagnostic(String text, int max) {
        if (text == null) {
            return null;
        }
        String sanitized = text
                .replaceAll("[A-Za-z]:\\\\[^\\s:]*", "<path>")
                .replaceAll("(/[\\w.\\-]+){2,}", "<path>");
        return truncate(sanitized, max);
    }

    /**
     * 把脚本请求的流程启动意图写入持久触发记录（Phase 4 可靠业务事件 —— IoT 脚本路径）。
     *
     * <p>原实现只发布进程内事件：无事务或进程退出即静默丢失，消费端按幂等键回写时也找不到任何行。
     * 现在意图与脚本执行同事务落 {@code sw_iot_process_trigger}（PENDING + 稳定幂等键 +
     * 发起目标身份），由 bpm 侧监听器执行，失败由恢复调度重投。</p>
     */
    private void persistProcessStartIntents(ScriptHostFunctions host, boolean sideEffect) {
        if (!sideEffect || host == null || host.getProcessStartRequests().isEmpty()) {
            // 试运行不产生副作用，不登记意图
            return;
        }
        for (ScriptHostFunctions.ProcessStartRequest request : host.getProcessStartRequests()) {
            com.sw.ck.iot.entity.IotProcessTrigger trigger = new com.sw.ck.iot.entity.IotProcessTrigger();
            trigger.setScriptId(request.scriptId());
            trigger.setDeviceId(request.deviceId());
            trigger.setTenantId(request.tenantId());
            trigger.setIdempotentKey(request.idempotentKey());
            trigger.setProcessTemplateKey(request.processTemplateKey());
            trigger.setTriggerSource(request.triggerSource() == null ? "SCRIPT" : request.triggerSource());
            trigger.setConfiguredBy(request.configuredBy());
            trigger.setStatus("PENDING");
            trigger.setRetryCount(0);
            trigger.setTriggerTime(java.time.LocalDateTime.now());
            trigger.setFormSnapshot(JSON.toJSONString(request.formData()));
            try {
                // 与规则路径同口径：唯一键冲突在保存点内发生，不污染调用方业务事务
                com.sw.ck.common.persistence.IdempotentInsert.execute(transactionManager,
                        () -> beans.processTriggerMapper.insert(trigger));
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                // 幂等：同幂等键已登记，不重复建行
                continue;
            }
        }
    }

    /**
     * Mapper/Bean 聚合（避免 ScriptEngineService 直接依赖全部 Mapper 造成构造膨胀）。
     */
    @Service
    public static class ScriptBeanAccess {
        final IotDeviceMapper deviceMapper;
        final IotConnectionMapper connectionMapper;
        final IotTopicMapper topicMapper;
        final IotCommandMapper commandMapper;
        final IotPropertyRecordMapper propertyRecordMapper;
        final IotEventRecordMapper eventRecordMapper;
        final IotProductMapper productMapper;
        final IotThingModelMapper thingModelMapper;
        final MqttBrokerManager mqttBrokerManager;
        final DomainEventPublisher eventPublisher;
        final IotScriptExecMapper scriptExecMapper;
        final IotScriptMapper scriptMapper;
        final IotScriptVersionMapper scriptVersionMapper;
        final IotProcessTriggerMapper processTriggerMapper;

        public ScriptBeanAccess(IotDeviceMapper deviceMapper,
                                IotConnectionMapper connectionMapper,
                                IotTopicMapper topicMapper,
                                IotCommandMapper commandMapper,
                                IotPropertyRecordMapper propertyRecordMapper,
                                IotEventRecordMapper eventRecordMapper,
                                IotProductMapper productMapper,
                                IotThingModelMapper thingModelMapper,
                                MqttBrokerManager mqttBrokerManager,
                                DomainEventPublisher eventPublisher,
                                IotScriptExecMapper scriptExecMapper,
                                IotScriptMapper scriptMapper,
                                IotScriptVersionMapper scriptVersionMapper,
                                IotProcessTriggerMapper processTriggerMapper) {
            this.deviceMapper = deviceMapper;
            this.connectionMapper = connectionMapper;
            this.topicMapper = topicMapper;
            this.commandMapper = commandMapper;
            this.propertyRecordMapper = propertyRecordMapper;
            this.eventRecordMapper = eventRecordMapper;
            this.productMapper = productMapper;
            this.thingModelMapper = thingModelMapper;
            this.mqttBrokerManager = mqttBrokerManager;
            this.eventPublisher = eventPublisher;
            this.scriptExecMapper = scriptExecMapper;
            this.scriptMapper = scriptMapper;
            this.scriptVersionMapper = scriptVersionMapper;
            this.processTriggerMapper = processTriggerMapper;
        }
    }

    /**
     * 查询脚本最近执行记录（辅助）。
     */
    public List<IotScriptExec> recentExecs(Long scriptId, int limit) {
        return beans.scriptExecMapper.selectList(new LambdaQueryWrapper<IotScriptExec>()
                .eq(IotScriptExec::getScriptId, scriptId)
                .orderByDesc(IotScriptExec::getCreateTime)
                .last("limit " + Math.max(1, Math.min(limit, 200))));
    }

    /**
     * 当前时间（测试可覆盖）。
     */
    LocalDateTime now() {
        return LocalDateTime.now();
    }
}
