package com.sw.ck.bpm.process.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 5 · IoT 契约边界的类路径层机械守门（方向 §4 验收门禁 3/4）。
 *
 * <p>本类运行在 {@code sw-bpm-process} 自己的编译/测试类路径上，因此可以直接证明
 * 「边界抽取后本模块再也看不到 IoT 实现」——比 POM 文本扫描更决定：</p>
 * <ul>
 *   <li><b>正向</b>：契约类型必须可达（{@code sw-basic-iot-api} 在类路径）；</li>
 *   <li><b>反向</b>：IoT 实现类型、MQTT/Paho、GraalJS/Truffle、Tencent IoT SDK 必须全部
 *       {@link ClassNotFoundException}；</li>
 *   <li><b>诚实依赖</b>：fastjson2 作为显式直接依赖必须可达（不再是传递获得）。</li>
 * </ul>
 */
class IotContractBoundaryIsolationTest {

    @Test
    @DisplayName("正向：IoT 契约类型在 bpm-process 类路径可达")
    void contractTypesAreReachable() {
        assertDoesNotThrow(() -> Class.forName("com.sw.ck.iot.api.IotDeviceFacade"));
        assertDoesNotThrow(() -> Class.forName("com.sw.ck.iot.api.IotProcessTriggerFacade"));
        assertDoesNotThrow(() -> Class.forName("com.sw.ck.iot.api.IotDeviceQueryFacade"));
        assertDoesNotThrow(() -> Class.forName("com.sw.ck.iot.api.IotFormContractChecker"));
        assertDoesNotThrow(() -> Class.forName("com.sw.ck.iot.event.IotProcessTriggerEvent"));
    }

    @Test
    @DisplayName("反向：IoT 实现类型在 bpm-process 类路径不可达（entity/mapper/service/job/script）")
    void iotImplementationTypesAreNotReachable() {
        String[] implementationTypes = {
                "com.sw.ck.iot.entity.IotDeviceCommand",
                "com.sw.ck.iot.entity.IotDevice",
                "com.sw.ck.iot.entity.IotProcessTrigger",
                "com.sw.ck.iot.mapper.IotDeviceCommandMapper",
                "com.sw.ck.iot.mapper.IotProcessTriggerMapper",
                "com.sw.ck.iot.service.CommandQueueService",
                "com.sw.ck.iot.service.RuleEngineService",
                "com.sw.ck.iot.service.IotDeviceService",
                "com.sw.ck.iot.job.CommandCompensationJob",
                "com.sw.ck.iot.job.ProcessTriggerRecoveryJob",
                "com.sw.ck.iot.script.ScriptEngineService",
                "com.sw.ck.iot.script.ScriptHostFunctions",
                "com.sw.ck.iot.api.impl.IotDeviceFacadeImpl",
                "com.sw.ck.iot.config.IotAutoConfiguration"};
        for (String type : implementationTypes) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type),
                    "IoT 实现类型不得出现在 bpm-process 类路径: " + type);
        }
    }

    @Test
    @DisplayName("反向：MQTT/Paho、GraalJS/Truffle、Tencent IoT SDK 在 bpm-process 类路径不可达")
    void heavyImplementationDependenciesAreNotReachable() {
        String[] heavyTypes = {
                "org.eclipse.paho.client.mqttv3.MqttClient",
                "org.eclipse.paho.mqttv5.client.MqttClient",
                "org.springframework.integration.mqtt.core.Mqttv5ClientManager",
                "org.graalvm.polyglot.Context",
                "org.graalvm.polyglot.Engine",
                "com.tencentcloudapi.iotexplorer.v20180123.IotexplorerClient"};
        for (String type : heavyTypes) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type),
                    "重型实现依赖不得出现在 bpm-process 类路径: " + type);
        }
    }

    @Test
    @DisplayName("诚实依赖：fastjson2 作为直接依赖可达（不再借 IoT 传递获得）")
    void fastjsonIsAnHonestDirectDependency() {
        assertDoesNotThrow(() -> Class.forName("com.alibaba.fastjson2.JSON"));
        assertDoesNotThrow(() -> Class.forName("com.alibaba.fastjson2.JSONObject"));
    }
}
