package com.sw.ck.iot.script;

import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRecordMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraalJS 沙箱测试：正常计算、宿主函数白名单、越权 API 拒绝、死循环超时、资源超限。
 */
@ExtendWith(MockitoExtension.class)
class GraalJsSandboxTest {

    @Mock
    private IotDeviceMapper deviceMapper;
    @Mock
    private IotConnectionMapper connectionMapper;
    @Mock
    private IotTopicMapper topicMapper;
    @Mock
    private IotCommandMapper commandMapper;
    @Mock
    private IotPropertyRecordMapper propertyRecordMapper;
    @Mock
    private IotEventRecordMapper eventRecordMapper;
    @Mock
    private IotProductMapper productMapper;
    @Mock
    private IotThingModelMapper thingModelMapper;
    @Mock
    private MqttBrokerManager mqttBrokerManager;
    @Mock
    private DomainEventPublisher eventPublisher;

    private GraalJsRunner runner;

    @BeforeEach
    void setUp() {
        runner = new GraalJsRunner();
    }

    private ScriptExecutionSpec spec(String source, boolean sideEffect, int timeoutMs) {
        ScriptExecutionSpec spec = new ScriptExecutionSpec();
        spec.setScriptId(1L);
        spec.setScriptVersion(1);
        spec.setLanguage("JS");
        spec.setSourceCode(source);
        spec.setTenantId(1L);
        spec.setSideEffectAllowed(sideEffect);
        spec.setInput(Map.of("value", 21));
        spec.setTimeoutMs(timeoutMs);
        return spec;
    }

    private ScriptHostFunctions host(ScriptExecutionSpec spec) {
        return new ScriptHostFunctions(deviceMapper, connectionMapper, topicMapper, commandMapper,
                propertyRecordMapper, eventRecordMapper, productMapper, thingModelMapper,
                mqttBrokerManager, eventPublisher, spec);
    }

    @Test
    void testNormalHandlerExecutes() {
        ScriptExecutionSpec spec = spec(
                "function handler(input) { return { doubled: input.value * 2 }; }", false, 5000);
        ScriptRunResult result = runner.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.getOutputJson().contains("42"));
    }

    @Test
    void testHostFunctionPublishAllowed() {
        // fun_publish 在副作用开启时走宿主校验路径：Topic 未配置应抛业务错误而非沙箱错误
        ScriptExecutionSpec spec = spec(
                "function handler(input) { try { fun_publish('t', 'p'); return {called: true}; } catch (e) { return {err: String(e)}; } }",
                true, 5000);
        ScriptRunResult result = runner.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.getOutputJson().contains("未配置或未启用"));
    }

    @Test
    void testSideEffectForbiddenInDryRun() {
        ScriptExecutionSpec spec = spec(
                "function handler(input) { try { fun_publish('t', 'p'); return {ok: true}; } catch (e) { return {err: String(e)}; } }",
                false, 5000);
        ScriptRunResult result = runner.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.getOutputJson().contains("试运行下被禁止"));
    }

    @Test
    void testHostClassAccessDenied() {
        ScriptExecutionSpec spec = spec(
                "function handler(input) { try { var s = Java.type('java.lang.System'); return {escaped: true}; } catch (e) { return {blocked: true}; } }",
                true, 5000);
        ScriptRunResult result = runner.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus());
        assertFalse(result.getOutputJson().contains("escaped"));
        assertTrue(result.getOutputJson().contains("blocked"));
    }

    @Test
    void testInfiniteLoopTimeout() {
        ScriptExecutionSpec spec = spec(
                "function handler(input) { while (true) {} }", false, 800);
        ScriptRunResult result = runner.run(spec, host(spec));
        assertEquals("TIMEOUT", result.getStatus());
    }

    @Test
    void testStatementLimit() {
        ScriptExecutionSpec spec = spec(
                "function handler(input) { var s = 0; for (var i = 0; i < 100000000; i++) { s += i; } return {s: s}; }",
                false, 30000);
        ScriptRunResult result = runner.run(spec, host(spec));
        assertNotEquals("SUCCESS", result.getStatus());
    }

    @Test
    void testValidateDetectsSyntaxError() {
        ScriptRunResult result = runner.validate("function handler( {");
        assertEquals("FAILED", result.getStatus());
    }
}
