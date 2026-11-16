package com.sw.ck.iot.vo;

import lombok.Data;

/**
 * 建立常驻 MQTT 连接的结果：是否已连接与恢复的订阅数。
 */
@Data
public class IotConnectResultVO {

    private boolean connected;

    private int subscriptions;
}
