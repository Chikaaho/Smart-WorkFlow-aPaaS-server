package com.sw.ck.bpm.process.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量审批汇总：逐项结果 + 成功/失败/总数/处理中计数。
 * <p>
 * 计数使用 {@code int}，保持 JSON 数值类型（Long 会被全局序列化为字符串）；
 * 同步契约：processing 恒为 0，由服务端显式给出。
 * </p>
 */
@Data
public class BatchActionSummaryDTO {

    private List<BatchActionResultDTO> results;

    private int success;

    private int failed;

    private int total;

    private int processing;
}
