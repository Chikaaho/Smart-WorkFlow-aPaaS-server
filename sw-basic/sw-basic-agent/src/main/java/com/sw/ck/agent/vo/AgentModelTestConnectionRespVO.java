package com.sw.ck.agent.vo;

import lombok.Data;

/**
 * 连通性测试响应 DTO。
 */
@Data
public class AgentModelTestConnectionRespVO {

    /** 是否连通成功；服务端可达（含 4xx 鉴权/路径问题）为 true，网络不可达为 false */
    private boolean success;

    /** 结果说明（不含 API Key 明文） */
    private String message;

    /** 探测耗时（毫秒） */
    private long latencyMs;
}
