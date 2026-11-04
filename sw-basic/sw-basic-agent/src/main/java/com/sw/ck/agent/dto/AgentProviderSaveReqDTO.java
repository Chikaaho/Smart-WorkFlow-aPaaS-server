package com.sw.ck.agent.dto;

import lombok.Data;

/**
 * 供应商新建/编辑请求（F1 阶段 1）。
 * <p>apiKey：新增时填明文（Service 加密落库）；编辑时留空 = 保留旧密文。</p>
 */
@Data
public class AgentProviderSaveReqDTO {

    private String name;

    private String apiFormat;

    private String baseUrl;

    /** 明文 API Key（编辑留空 = 保留） */
    private String apiKey;

    private Boolean enabled;

    private String remark;

}
