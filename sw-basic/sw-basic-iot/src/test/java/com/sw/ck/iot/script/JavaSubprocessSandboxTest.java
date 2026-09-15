package com.sw.ck.iot.script;

import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRecordMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.entity.IotTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java 受控脚本子进程隔离测试：RPC 正常执行、宿主函数调用、文件写入被策略拒绝、超时强杀。
 */
@ExtendWith(MockitoExtension.class)
class JavaSubprocessSandboxTest {

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

    private JavaSubprocessExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new JavaSubprocessExecutor();
    }

    private ScriptExecutionSpec spec(String source, boolean sideEffect, int timeoutMs) {
        ScriptExecutionSpec spec = new ScriptExecutionSpec();
        spec.setScriptId(2L);
        spec.setScriptVersion(1);
        spec.setLanguage("JAVA");
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

    private static final String HEADER = """
            import com.sw.ck.iot.script.api.IotJavaScript;
            import com.sw.ck.iot.script.api.IotScriptApi;
            import java.util.HashMap;
            import java.util.Map;

            public class DemoScript implements IotJavaScript {
                @Override
                public Object execute(IotScriptApi api, Map<String, Object> input) {
            """;

    @Test
    void testNormalExecutionAndHostLog() {
        String source = HEADER + """
                    Map<String, Object> out = new HashMap<>(input);
                    api.funLog("INFO", "hello", null);
                    out.put("done", true);
                    return out;
                }
            }
            """;
        ScriptExecutionSpec spec = spec(source, false, 20000);
        ScriptRunResult result = executor.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus(), () -> "error=" + result.getError());
        assertTrue(result.getOutputJson().contains("done"));
        assertTrue(result.getLogs().stream().anyMatch(l -> l.contains("hello")));
    }

    @Test
    void testSideEffectGuarded() {
        String source = HEADER + """
                    try {
                        api.funPublish("t", "p", null);
                        return Map.of("ok", true);
                    } catch (RuntimeException e) {
                        return Map.of("err", String.valueOf(e.getMessage()));
                    }
                }
            }
            """;
        ScriptExecutionSpec spec = spec(source, false, 20000);
        ScriptRunResult result = executor.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.getOutputJson().contains("试运行下被禁止"));
    }

    @Test
    void testJavaCorrelationAcrossActionPublishAndEvent() {
        IotDevice device = new IotDevice();
        device.setId(42L);
        device.setTenantId(1L);
        device.setProductRefId(7L);
        IotProduct product = new IotProduct();
        product.setId(7L);
        product.setConnType("MQTT");
        product.setPublishedModelId(8L);
        IotThingModel model = new IotThingModel();
        model.setId(8L);
        model.setContentJson("{\"properties\":[],\"events\":[{\"id\":\"overheat\"}],\"actions\":[{\"id\":\"reset\"}]}");
        IotTopic topic = new IotTopic();
        topic.setId(9L);
        topic.setConnId(10L);
        topic.setTopic("owner/p21/down/cmd");
        topic.setEnabled(1);
        topic.setQos(1);
        IotConnection connection = new IotConnection();
        connection.setId(10L);
        connection.setEnabled(1);

        when(deviceMapper.selectById(42L)).thenReturn(device);
        when(productMapper.selectById(7L)).thenReturn(product);
        when(thingModelMapper.selectById(8L)).thenReturn(model);
        when(topicMapper.selectOne(any())).thenReturn(topic);
        when(connectionMapper.selectById(10L)).thenReturn(connection);
        when(mqttBrokerManager.publish(eq(10L), eq("owner/p21/down/cmd"), eq("JAVA:21"), eq(1), eq(false)))
                .thenReturn(Map.of("brokerAck", true, "messageId", 11));
        doAnswer(invocation -> {
            com.sw.ck.iot.entity.IotCommand command = invocation.getArgument(0);
            command.setId(command.getCapabilityType().equals("INVOKE_ACTION") ? 100L : 101L);
            return 1;
        }).when(commandMapper).insert(any(com.sw.ck.iot.entity.IotCommand.class));
        doAnswer(invocation -> {
            com.sw.ck.iot.entity.IotEventRecord event = invocation.getArgument(0);
            event.setId(102L);
            return 1;
        }).when(eventRecordMapper).insert(any(com.sw.ck.iot.entity.IotEventRecord.class));

        String source = HEADER + """
                    Map<String, Object> action = api.funInvokeAction(42L, "reset", Map.of("seq", "21"), null);
                    Map<String, Object> publish = api.funPublish("owner/p21/down/cmd", "JAVA:21", null);
                    Map<String, Object> event = api.funEmitEvent(42L, "overheat", Map.of("seq", "21"));
                    Map<String, Object> out = new HashMap<>();
                    out.put("action", action.get("commandId"));
                    out.put("publish", publish.get("commandId"));
                    out.put("event", event.get("recordId"));
                    out.put("correlationId", action.get("correlationId"));
                    return out;
                }
            }
            """;
        ScriptExecutionSpec spec = spec(source, true, 20000);
        spec.setDeviceId(42L);
        spec.setCorrelationId("java-chain-correlation");
        ScriptRunResult result = executor.run(spec, host(spec));

        assertEquals("SUCCESS", result.getStatus(), () -> "error=" + result.getError());
        assertTrue(result.getOutputJson().contains("java-chain-correlation"));
        org.mockito.ArgumentCaptor<com.sw.ck.iot.entity.IotCommand> commandCaptor =
                org.mockito.ArgumentCaptor.forClass(com.sw.ck.iot.entity.IotCommand.class);
        org.mockito.Mockito.verify(commandMapper, org.mockito.Mockito.times(2)).insert(commandCaptor.capture());
        assertTrue(commandCaptor.getAllValues().stream()
                .allMatch(command -> "java-chain-correlation".equals(command.getCorrelationId())));
        org.mockito.ArgumentCaptor<com.sw.ck.iot.entity.IotEventRecord> eventCaptor =
                org.mockito.ArgumentCaptor.forClass(com.sw.ck.iot.entity.IotEventRecord.class);
        org.mockito.Mockito.verify(eventRecordMapper).insert(eventCaptor.capture());
        assertEquals("java-chain-correlation", eventCaptor.getValue().getCorrelationId());
    }

    @Test
    void testFileWriteBlockedBySecurityManager() {
        String source = HEADER + """
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("pwned.txt"), "x");
                        return Map.of("write", "allowed");
                    } catch (Exception e) {
                        return Map.of("write", "blocked");
                    }
                }
            }
            """;
        ScriptExecutionSpec spec = spec(source, false, 20000);
        ScriptRunResult result = executor.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.getOutputJson().contains("blocked"), result.getOutputJson());
    }

    @Test
    void testProcessExecBlocked() {
        String source = HEADER + """
                    try {
                        new ProcessBuilder("id").start();
                        return Map.of("exec", "allowed");
                    } catch (Exception e) {
                        return Map.of("exec", "blocked");
                    }
                }
            }
            """;
        ScriptExecutionSpec spec = spec(source, false, 20000);
        ScriptRunResult result = executor.run(spec, host(spec));
        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.getOutputJson().contains("blocked"), result.getOutputJson());
    }

    @Test
    void testInfiniteLoopForceKilled() {
        String source = HEADER + """
                    while (true) { }
                }
            }
            """;
        ScriptExecutionSpec spec = spec(source, false, 1500);
        ScriptRunResult result = executor.run(spec, host(spec));
        assertEquals("TIMEOUT", result.getStatus());
    }

    @Test
    void testCompileErrorReported() {
        String source = HEADER + """
                    return syso;
                }
            }
            """;
        ScriptExecutionSpec spec = spec(source, false, 5000);
        ScriptRunResult result = executor.run(spec, host(spec));
        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getError().contains("编译失败"));
    }
}
