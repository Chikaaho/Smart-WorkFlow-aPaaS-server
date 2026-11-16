package com.sw.ck.openapi.biz.dto;

import lombok.Data;

/**
 * 出站回调恢复重发结果：事件、实例、是否去重与是否已投递。
 */
@Data
public class OpenApiCallbackResendDTO {

    private String event;

    private String processInstanceId;

    private boolean deduped;

    private boolean delivered;
}
