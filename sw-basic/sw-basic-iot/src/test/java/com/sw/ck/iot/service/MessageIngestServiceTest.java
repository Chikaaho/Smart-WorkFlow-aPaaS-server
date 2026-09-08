package com.sw.ck.iot.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotCommand;
import com.sw.ck.iot.entity.IotMessageLog;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotPropertyRecord;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRecordMapper;
import com.sw.ck.iot.mapper.IotMessageLogMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息统一入口测试：属性解析、物模型校验拒绝、去重、失败记录。
 */
@ExtendWith(MockitoExtension.class)
class MessageIngestServiceTest {

    @Mock
    private IotTopicMapper topicMapper;
    @Mock
    private IotDeviceMapper deviceMapper;
    @Mock
    private IotMessageLogMapper messageLogMapper;
    @Mock
    private IotPropertyRecordMapper propertyRecordMapper;
    @Mock
    private IotEventRecordMapper eventRecordMapper;
    @Mock
    private IotCommandMapper commandMapper;
    @Mock
    private IotProductMapper productMapper;
    @Mock
    private IotThingModelMapper thingModelMapper;
    @Mock
    private RuleEngineService ruleEngineService;
    @Mock
    private IotScriptService scriptService;

    private MessageIngestService service;

    private IotTopic topicCfg;
    private IotDevice device;
    private IotProduct product;
    private IotThingModel model;

    @BeforeEach
    void setUp() {
        service = new MessageIngestService(topicMapper, deviceMapper, messageLogMapper,
                propertyRecordMapper, eventRecordMapper, commandMapper, productMapper,
                thingModelMapper, ruleEngineService, scriptService);

        topicCfg = new IotTopic();
        topicCfg.setId(10L);
        topicCfg.setConnId(1L);
        topicCfg.setProductId(100L);
        topicCfg.setTopic("sw/+/property");
        topicCfg.setDirection("UP");
        topicCfg.setQos(1);
        topicCfg.setPayloadType("PROPERTY");
        topicCfg.setEnabled(1);

        device = new IotDevice();
        device.setId(7L);
        device.setDeviceKey("dev-001");
        device.setProductRefId(100L);
        device.setTenantId(1L);

        product = new IotProduct();
        product.setId(100L);
        product.setPublishedModelId(1000L);

        model = new IotThingModel();
        model.setId(1000L);
        model.setContentJson(JSON.toJSONString(Map.of(
                "properties", List.of(Map.of("id", "temperature", "dataType", "double"))
        )));
    }

    @Test
    void testIngestProperty_ParsedAndRuleDispatched() {
        when(topicMapper.selectList(any())).thenReturn(List.of(topicCfg));
        when(messageLogMapper.selectCount(any())).thenReturn(0L);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(productMapper.selectById(100L)).thenReturn(product);
        when(thingModelMapper.selectById(1000L)).thenReturn(model);
        when(propertyRecordMapper.selectOne(any())).thenReturn(null);

        service.ingest(1L, "sw/dev-001/property",
                "{\"properties\":{\"temperature\":26.5}}", 1, "mid-1");

        ArgumentCaptor<IotMessageLog> logCaptor = ArgumentCaptor.forClass(IotMessageLog.class);
        verify(messageLogMapper).insert(logCaptor.capture());
        assertEquals("PARSED", logCaptor.getValue().getParseStatus());
        verify(propertyRecordMapper).insert(any(IotPropertyRecord.class));
        verify(ruleEngineService).onProperty(any(), eq("temperature"), anyString(), any(), any(), anyString());
    }

    @Test
    void testIngestDuplicate_Deduplicated() {
        when(topicMapper.selectList(any())).thenReturn(List.of(topicCfg));
        when(messageLogMapper.selectCount(any())).thenReturn(1L);

        service.ingest(1L, "sw/dev-001/property",
                "{\"properties\":{\"temperature\":26.5}}", 1, "mid-1");

        ArgumentCaptor<IotMessageLog> logCaptor = ArgumentCaptor.forClass(IotMessageLog.class);
        verify(messageLogMapper).insert(logCaptor.capture());
        assertEquals("DUPLICATED", logCaptor.getValue().getParseStatus());
        verify(propertyRecordMapper, never()).insert(any(IotPropertyRecord.class));
        verify(ruleEngineService, never()).onProperty(any(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void testIngestUndeclaredProperty_FailedRecord() {
        when(topicMapper.selectList(any())).thenReturn(List.of(topicCfg));
        when(messageLogMapper.selectCount(any())).thenReturn(0L);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(productMapper.selectById(100L)).thenReturn(product);
        when(thingModelMapper.selectById(1000L)).thenReturn(model);

        service.ingest(1L, "sw/dev-001/property",
                "{\"properties\":{\"hackerProperty\":1}}", 1, "mid-2");

        ArgumentCaptor<IotMessageLog> logCaptor = ArgumentCaptor.forClass(IotMessageLog.class);
        verify(messageLogMapper).insert(logCaptor.capture());
        assertEquals("FAILED", logCaptor.getValue().getParseStatus());
        assertTrue(logCaptor.getValue().getParseError().contains("未在已发布物模型中声明"));
        verify(propertyRecordMapper, never()).insert(any(IotPropertyRecord.class));
    }

    @Test
    void testActionResultDuplicateDoesNotOverwriteTerminalCommand() {
        topicCfg.setProductId(null);
        topicCfg.setTopic("owner/p21/up/ack");
        topicCfg.setPayloadType(MessageIngestService.TYPE_ACTION_RESULT);
        when(topicMapper.selectList(any())).thenReturn(List.of(topicCfg));
        when(messageLogMapper.selectCount(any())).thenReturn(0L);

        IotCommand command = new IotCommand();
        command.setId(99L);
        command.setStatus("BROKER_ACK");
        command.setCorrelationId("corr-h2");
        when(commandMapper.selectById(99L)).thenReturn(command);
        when(commandMapper.updateById(any(IotCommand.class))).thenAnswer(invocation -> {
            IotCommand patch = invocation.getArgument(0);
            if (patch.getStatus() != null) command.setStatus(patch.getStatus());
            if (patch.getResultJson() != null) command.setResultJson(patch.getResultJson());
            if (patch.getReplyTime() != null) command.setReplyTime(patch.getReplyTime());
            return 1;
        });

        service.ingest(1L, "owner/p21/up/ack",
                "{\"commandId\":99,\"correlationId\":\"corr-h2\",\"status\":\"SUCCESS\","
                        + "\"result\":{\"value\":\"first\"}}", 1, "ack-1");
        var firstReplyTime = command.getReplyTime();
        assertEquals("SUCCESS", command.getStatus());
        assertEquals("{\"value\":\"first\"}", command.getResultJson());
        assertNotNull(firstReplyTime);

        service.ingest(1L, "owner/p21/up/ack",
                "{\"commandId\":99,\"correlationId\":\"corr-h2\",\"status\":\"SUCCESS\","
                        + "\"result\":{\"value\":\"duplicate\"}}", 1, "ack-2");

        assertEquals("SUCCESS", command.getStatus());
        assertEquals("{\"value\":\"first\"}", command.getResultJson());
        assertEquals(firstReplyTime, command.getReplyTime());
        verify(commandMapper, org.mockito.Mockito.times(1)).updateById(any(IotCommand.class));
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
