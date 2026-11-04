package com.sw.ck.agent.dto;

import lombok.Data;

/**
 * 图级设置请求（D022-06/阶段 2 超时）。
 */
@Data
public class AgentGraphSettingsReqDTO {

    /** 图级执行超时（秒，1–3600） */
    private Integer executionTimeoutSeconds;
}
