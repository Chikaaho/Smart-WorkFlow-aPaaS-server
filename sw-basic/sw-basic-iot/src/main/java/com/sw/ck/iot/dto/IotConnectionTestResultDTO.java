package com.sw.ck.iot.dto;

import lombok.Data;

/**
 * 连接测试结果：分类、诊断详情与健康状态。
 */
@Data
public class IotConnectionTestResultDTO {

    private String category;

    private String detail;

    private String healthStatus;
}
