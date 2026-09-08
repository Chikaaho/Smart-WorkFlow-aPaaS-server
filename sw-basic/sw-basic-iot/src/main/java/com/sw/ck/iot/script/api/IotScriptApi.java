package com.sw.ck.iot.script.api;

import java.util.Map;

/**
 * 受控脚本宿主函数接口（JS 与 Java 同语义）。
 * <p>
 * 两种语言暴露同一组 {@code fun_*} 能力、错误码与状态语义；
 * 脚本不得通过其他途径访问连接凭证、宿主文件、数据库或任意网络。
 * </p>
 */
public interface IotScriptApi {

    /**
     * fun_publish(topic, payload, options)：向授权连接和主题发布消息。
     *
     * @return {commandId, brokerAck}
     */
    Map<String, Object> funPublish(String topic, String payload, Map<String, Object> options);

    /**
     * fun_subscribe(topicFilter, options)：声明授权主题订阅（记录声明，消息交回脚本处理）。
     */
    Map<String, Object> funSubscribe(String topicFilter, Map<String, Object> options);

    /**
     * fun_getProperty(deviceId, propertyId)：读取授权设备最新属性。
     *
     * @return {value, reportTime}
     */
    Map<String, Object> funGetProperty(Long deviceId, String propertyId);

    /**
     * fun_setProperty(deviceId, propertyId, value, options)：按物模型校验后生成属性设置命令。
     */
    Map<String, Object> funSetProperty(Long deviceId, String propertyId, Object value,
                                       Map<String, Object> options);

    /**
     * fun_emitEvent(deviceId, eventId, payload)：按已发布物模型写入统一设备事件。
     */
    Map<String, Object> funEmitEvent(Long deviceId, String eventId, Object payload);

    /**
     * fun_invokeAction(deviceId, actionId, input, options)：校验能力与参数后调用设备行为。
     */
    Map<String, Object> funInvokeAction(Long deviceId, String actionId, Object input,
                                        Map<String, Object> options);

    /**
     * fun_startProcess(templateKey, formData, options)：仅允许已发布且允许 IoT 接入的流程模板。
     *
     * @return {triggerId, status}
     */
    Map<String, Object> funStartProcess(String templateKey, Map<String, Object> formData,
                                        Map<String, Object> options);

    /**
     * fun_log(level, message, fields)：写入脱敏限量脚本日志。
     */
    void funLog(String level, String message, Map<String, Object> fields);
}
