package com.sw.ck.openapi.biz.vo;

import lombok.Data;

/**
 * 开放接口流程状态：实例、业务键与状态字符串（实例不存在为 NOT_FOUND）。
 */
@Data
public class OpenApiStatusVO {

    private String processInstanceId;

    private String businessKey;

    private String status;
}
