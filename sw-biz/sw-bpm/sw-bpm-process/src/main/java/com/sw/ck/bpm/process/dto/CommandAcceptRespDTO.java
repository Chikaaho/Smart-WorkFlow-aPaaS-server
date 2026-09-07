package com.sw.ck.bpm.process.dto;

import lombok.Data;

/**
 * 命令受理响应：受理标识 + 当前状态。
 * <p>
 * 受理（ACCEPTED）不等于业务已完成；实际结果按 commandId 回查。
 * </p>
 */
@Data
public class CommandAcceptRespDTO {

    public static final String STATUS_ACCEPTED = "ACCEPTED";

    private Long commandId;

    private String commandKey;

    private String commandType;

    private String channel;

    private String status = STATUS_ACCEPTED;

    /** true = 本次新建受理；false = 命中既有受理（幂等返回）。 */
    private boolean duplicated;
}
