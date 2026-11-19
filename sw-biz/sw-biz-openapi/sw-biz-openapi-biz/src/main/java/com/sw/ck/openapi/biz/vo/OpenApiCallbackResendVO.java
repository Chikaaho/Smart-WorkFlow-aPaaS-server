package com.sw.ck.openapi.biz.vo;

import lombok.Data;

/**
 * 出站回调恢复重发结果：事件、实例、是否去重与是否已投递。
 */
@Data
public class OpenApiCallbackResendVO {

    private String event;

    private String processInstanceId;

    private boolean deduped;

    private boolean delivered;
}
