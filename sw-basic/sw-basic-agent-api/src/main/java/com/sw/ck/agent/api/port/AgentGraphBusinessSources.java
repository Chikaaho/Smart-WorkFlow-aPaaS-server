package com.sw.ck.agent.api.port;

/**
 * 执行请求业务来源常量（E1-7，D52：varchar + String，不建 enum）。
 * 存量行与未标注调用为 null，读侧按 {@link #DIRECT} 展示。
 */
public final class AgentGraphBusinessSources {

    /** 流程服务节点（Graph 执行器）触发。 */
    public static final String PROCESS = "PROCESS";
    /** 表单服务端动作触发。 */
    public static final String FORM = "FORM";
    /** 直接调用（执行页/调试/IoT 桥等既有入口）。 */
    public static final String DIRECT = "DIRECT";

    private AgentGraphBusinessSources() {
    }
}
