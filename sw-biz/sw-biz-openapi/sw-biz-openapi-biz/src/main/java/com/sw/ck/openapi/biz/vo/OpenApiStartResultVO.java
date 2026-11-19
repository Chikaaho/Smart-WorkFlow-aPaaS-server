package com.sw.ck.openapi.biz.vo;

import lombok.Data;

/**
 * 开放接口外部发起结果：业务记录键与是否幂等重放。
 */
@Data
public class OpenApiStartResultVO {

    private String recordId;

    private boolean idempotentReplay;
}
