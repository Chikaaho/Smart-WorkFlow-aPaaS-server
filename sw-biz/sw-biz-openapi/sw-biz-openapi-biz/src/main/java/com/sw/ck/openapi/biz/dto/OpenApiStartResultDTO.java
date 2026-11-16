package com.sw.ck.openapi.biz.dto;

import lombok.Data;

/**
 * 开放接口外部发起结果：业务记录键与是否幂等重放。
 */
@Data
public class OpenApiStartResultDTO {

    private String recordId;

    private boolean idempotentReplay;
}
